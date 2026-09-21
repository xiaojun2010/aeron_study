package org.example.demo05;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===========================================================================
 * Demo05 —— IdleStrategy：Subscriber 空转时如何让出 CPU
 * ===========================================================================
 *
 * 对应文档《01.aeron 零基础入门》第 7 节末尾提到的四个 IdleStrategy：
 *
 *   BusySpinIdleStrategy   —— 完全不让出 CPU（Thread.onSpinWait），延迟最低，CPU 100%
 *   YieldingIdleStrategy   —— Thread.yield()，让出 CPU 但仍会立即回来
 *   SleepingIdleStrategy   —— parkNanos，最省 CPU，但延迟会明显上升
 *   BackoffIdleStrategy    —— 先 spin，再 yield，再 park，逐级退让（生产最常用）
 *
 * 本 demo 让 Publisher 每 500us 发一条，运行 3 秒，测量在不同 IdleStrategy 下
 * Subscriber 的处理速率（收到多少条）与逻辑上的"空 poll 次数"。
 *
 * 注意：真实性能对比需要绑核、warmup、隔离机器；本 demo 只是"感受一下 API"。
 */
public class Demo05IdleStrategy {

    private static final Logger log = LogManager.getLogger(Demo05IdleStrategy.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1005;

    public static void main(String[] args) throws Exception {

        // 依次跑四种策略
        runWith("BusySpin", new BusySpinIdleStrategy());
        runWith("Yielding", new YieldingIdleStrategy());
        runWith("Sleeping-1ms", new SleepingIdleStrategy(TimeUnit.MILLISECONDS.toNanos(1)));
        // BackoffIdleStrategy(maxSpins, maxYields, minParkNs, maxParkNs)
        // 大意：先自旋 maxSpins 次 → yield maxYields 次 → 从 minParkNs 逐渐涨到 maxParkNs
        runWith("Backoff", new BackoffIdleStrategy(
                100,   // maxSpins
                10,    // maxYields
                TimeUnit.MICROSECONDS.toNanos(1),
                TimeUnit.MICROSECONDS.toNanos(100)));
    }

    private static void runWith(String name, IdleStrategy idleStrategy) throws Exception {
        log.info("======== 使用 IdleStrategy = {} ========", name);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong received = new AtomicLong(0);
        AtomicLong emptyPolls = new AtomicLong(0);

        try (MediaDriver mediaDriver = MediaDriver.launchEmbedded();
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            while (!publication.isConnected() || !subscription.isConnected()) {
                Thread.sleep(5);
            }

            // -------- Subscriber --------
            Thread subscriberThread = new Thread(() -> {
                FragmentHandler handler = (buf, offset, length, header) -> received.incrementAndGet();
                while (running.get()) {
                    int fragments = subscription.poll(handler, 10);
                    if (fragments == 0) {
                        emptyPolls.incrementAndGet();
                        // 关键：使用 IdleStrategy 的 idle() 让出 CPU
                        //   - 传入本轮实际处理的 work 数（0 = 完全没干活，1+ = 有干活）
                        //   - IdleStrategy 内部会根据 "连续空转多少次" 决定退让力度
                        idleStrategy.idle(0);
                    } else {
                        // 有工作就 reset，回到最积极状态
                        idleStrategy.reset();
                    }
                }
            }, "sub-" + name);
            subscriberThread.start();

            // -------- Publisher (main 线程) --------
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
            buffer.putLong(0, 0L); // 占位

            long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            long sent = 0;
            while (System.nanoTime() < endAt) {
                buffer.putLong(0, sent);
                long r = publication.offer(buffer, 0, 8);
                if (r >= 0) {
                    sent++;
                }
                // 大约每 500us 发一条：LockSupport 精度不够但用得起
                java.util.concurrent.locks.LockSupport.parkNanos(500_000);
            }

            // 等 subscriber 消化
            long waitEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() < waitEnd && received.get() < sent) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }

            running.set(false);
            subscriberThread.join();

            log.info("  发送={}  收到={}  空poll次数={}", sent, received.get(), emptyPolls.get());
            log.info("  说明：空 poll 次数越大，说明 Subscriber 在'白转'——");
            log.info("        BusySpin/Yielding 空转次数会很多但 CPU 高，");
            log.info("        Sleeping 空转次数少但延迟高，Backoff 折中。");
        }
    }
}
