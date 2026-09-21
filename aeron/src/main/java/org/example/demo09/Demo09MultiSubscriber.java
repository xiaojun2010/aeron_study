package org.example.demo09;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ===========================================================================
 * Demo09 —— 多 Subscriber：Aeron 的"广播语义"演示
 * ===========================================================================
 *
 * 一个非常重要但初学者常搞混的问题：
 *
 *   Q：一份 Publication 发出的消息，同一个 stream 上的多个 Subscription 都能收到吗？
 *   A：可以。在 IPC 里，每个 Subscription 都是 term buffer 的独立读取端，
 *      Publication 只是往共享 log 里写；每个订阅者按自己的 consumer position 读，
 *      互不影响。
 *
 * 也就是说：
 *
 *   Publication ──> aeron:ipc/stream=1009
 *                       │
 *                       ├──> Subscription A (行情策略 Strategy)
 *                       ├──> Subscription B (风控 Risk)
 *                       └──> Subscription C (录制 Recorder)
 *
 *   每个订阅者都能收到完整的 100 条消息，而不是"抢一份"。
 *
 * 这和 Kafka 的 consumer-group 完全不同：
 *   * Kafka：同一 group 内的多个 consumer 会瓜分 partition
 *   * Aeron：多个 Subscription 是"独立的读游标"，天然广播
 *
 * 如果你想做"多消费者负载均衡"，需要在业务层用其它 stream / 分片，不是 Aeron 天然支持。
 */
public class Demo09MultiSubscriber {

    private static final Logger log = LogManager.getLogger(Demo09MultiSubscriber.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1009;
    private static final int MSG_COUNT = 100;

    public static void main(String[] args) throws Exception {

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger cntA = new AtomicInteger(0);
        AtomicInteger cntB = new AtomicInteger(0);
        AtomicInteger cntC = new AtomicInteger(0);

        try (MediaDriver mediaDriver = MediaDriver.launchEmbedded();
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             // 三个独立的 Subscription，全部订阅同一个 (channel, streamId)
             Subscription subA = aeron.addSubscription(CHANNEL, STREAM_ID);
             Subscription subB = aeron.addSubscription(CHANNEL, STREAM_ID);
             Subscription subC = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            while (!publication.isConnected()
                    || !subA.isConnected()
                    || !subB.isConnected()
                    || !subC.isConnected()) {
                Thread.sleep(10);
            }

            // 起三个独立线程各自消费
            Thread tA = startConsumer("Strategy", subA, cntA, running);
            Thread tB = startConsumer("Risk",     subB, cntB, running);
            Thread tC = startConsumer("Recorder", subC, cntC, running);

            // -------- Publisher --------
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
            for (int i = 0; i < MSG_COUNT; i++) {
                String msg = "broadcast-" + i;
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                buffer.putBytes(0, bytes);
                long r;
                while ((r = publication.offer(buffer, 0, bytes.length)) < 0) {
                    Thread.yield();
                }
            }
            log.info("[Pub] 已发送 {} 条", MSG_COUNT);

            // 等所有订阅者都消费完
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline
                    && (cntA.get() < MSG_COUNT || cntB.get() < MSG_COUNT || cntC.get() < MSG_COUNT)) {
                Thread.sleep(20);
            }

            running.set(false);
            tA.join();
            tB.join();
            tC.join();

            log.info("========================================");
            log.info("Strategy 收到 : {}", cntA.get());
            log.info("Risk     收到 : {}", cntB.get());
            log.info("Recorder 收到 : {}", cntC.get());
            log.info("如果都是 {}，说明 Aeron 是广播语义", MSG_COUNT);
            log.info("========================================");
        }
    }

    private static Thread startConsumer(String name, Subscription sub,
                                        AtomicInteger cnt, AtomicBoolean running) {
        Thread t = new Thread(() -> {
            FragmentHandler handler = (buffer, offset, length, header) -> {
                int n = cnt.incrementAndGet();
                if (n % 25 == 0) {
                    byte[] data = new byte[length];
                    buffer.getBytes(offset, data);
                    log.info("[{}] 第 {} 条: {}",
                            name, n, new String(data, StandardCharsets.UTF_8));
                }
            };
            while (running.get()) {
                if (sub.poll(handler, 10) == 0) {
                    Thread.yield();
                }
            }
        }, "sub-" + name);
        t.start();
        return t;
    }
}
