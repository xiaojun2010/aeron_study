package org.example.demo02;

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

/**
 * ===========================================================================
 * Demo02 —— 拆分 Publisher / Subscriber 到独立线程
 * ===========================================================================
 *
 * 相比 Demo01 的"发一条 poll 一条"，这里更接近真实使用方式：
 *
 *   Publisher 线程 ──> aeron:ipc ──> Subscriber 线程
 *              （同一个 JVM，Embedded MediaDriver 仍然共用）
 *
 * 目标：
 *   1. 演示 Publisher / Subscriber 独立线程运行的正确骨架
 *   2. Subscriber 端演示典型的 "while(running) { poll(); }" 循环
 *   3. 引入 fragmentLimit 概念（poll 一次最多消费的 fragment 数）
 *
 * 特别提醒（Aeron 的常见错误做法）：
 *   ✗ 不要在 Subscriber 里 sleep 500ms 再 poll
 *   ✗ 不要相信"消息一到就 callback"
 *   ✓ 正确做法：循环 poll，无消息时再决定用什么 IdleStrategy 让出 CPU
 */
public class Demo02SplitPubSub {

    private static final Logger log = LogManager.getLogger(Demo02SplitPubSub.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1002;

    /** 总共要发送多少条消息 */
    private static final int MSG_COUNT = 20;

    public static void main(String[] args) throws Exception {

        // 用一个 volatile flag 控制 Subscriber 线程什么时候退出。
        // AtomicBoolean 只是为了写法简单，读的性能已足够；核心是"线程可见性"。
        AtomicBoolean running = new AtomicBoolean(true);

        try (MediaDriver mediaDriver = MediaDriver.launchEmbedded();
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(mediaDriver.aeronDirectoryName()))) {

            // -----------------------------------------------------------------
            // Subscriber 线程
            // -----------------------------------------------------------------
            Thread subscriberThread = new Thread(() -> {
                // 每个线程创建自己需要的 Subscription
                try (Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

                    // FragmentHandler：Aeron 每处理一个 fragment 就回调一次
                    FragmentHandler handler = (buffer, offset, length, header) -> {
                        byte[] data = new byte[length];
                        buffer.getBytes(offset, data);
                        log.info("[Sub] 收到: {} (sessionId={}, position={})",
                                new String(data, StandardCharsets.UTF_8),
                                header.sessionId(),
                                header.position());
                    };

                    // 典型的 Aeron 消费循环：
                    //   while (running) { poll(); }
                    // 现在我们没有 IdleStrategy，直接 Thread.yield()。
                    // 这样已经能跑通，但 CPU 会飙到 100%（后面 demo05 讲 IdleStrategy）。
                    while (running.get()) {
                        int fragments = subscription.poll(handler, 10 /* fragmentLimit */);
                        if (fragments == 0) {
                            Thread.yield();
                        }
                    }
                }
            }, "aeron-subscriber");
            subscriberThread.start();

            // -----------------------------------------------------------------
            // Publisher 线程（也可以直接用 main 线程发，这里单独开是为了对称）
            // -----------------------------------------------------------------
            Thread publisherThread = new Thread(() -> {
                try (Publication publication = aeron.addPublication(CHANNEL, STREAM_ID)) {

                    // 等 Publication 建立连接（Subscription 已经 add 了，一般很快就 isConnected）
                    while (!publication.isConnected()) {
                        Thread.yield();
                    }

                    UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));

                    for (int i = 0; i < MSG_COUNT; i++) {
                        String msg = "tick-" + i;
                        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                        buffer.putBytes(0, bytes);

                        // 简易重试：offer 失败就 yield 再试
                        // 生产代码应区分 BACK_PRESSURED / NOT_CONNECTED 等，见 demo04
                        long r;
                        while ((r = publication.offer(buffer, 0, bytes.length)) < 0L) {
                            Thread.yield();
                        }
                        log.info("[Pub] 已发送: {} (pos={})", msg, r);

                        // 用一点 sleep 让日志更好看，实际低延迟场景不会这样干
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }, "aeron-publisher");
            publisherThread.start();

            // 等 Publisher 发完
            publisherThread.join();

            // 给 Subscriber 一点时间把最后几条 poll 出来
            Thread.sleep(500);
            running.set(false);
            subscriberThread.join();

            log.info("Demo02 结束。");
        }
    }
}
