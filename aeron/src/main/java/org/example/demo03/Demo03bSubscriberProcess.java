package org.example.demo03;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;

/**
 * ===========================================================================
 * Demo03b —— 独立进程运行 Subscriber
 * ===========================================================================
 *
 * 前置：先启动 Demo03aStandaloneMediaDriver，让 driver 常驻。
 *
 * 本进程不启 MediaDriver，只作为 Aeron Client 连过去。
 * 由此你能真正体会 Aeron 的架构：
 *
 *        MediaDriver (独立 JVM，Demo03a)
 *              ▲          ▲
 *              │          │
 *   Subscription        Publication
 *   (本 JVM)             (Demo03c JVM)
 */
public class Demo03bSubscriberProcess {

    private static final Logger log = LogManager.getLogger(Demo03bSubscriberProcess.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1003;

    public static void main(String[] args) {

        // 连接到独立 driver。这里没有指定 aeronDirectoryName，
        // 就会用 Aeron 的默认目录，与 Demo03a 使用 launch()（默认目录）配套。
        // 如果你在 Demo03a 里显式指定了 aeronDirectoryName，这里也要一致。
        try (Aeron aeron = Aeron.connect(new Aeron.Context());
             Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            FragmentHandler handler = (buffer, offset, length, header) -> {
                byte[] data = new byte[length];
                buffer.getBytes(offset, data);
                log.info("[Sub-Process] 收到: {}", new String(data, StandardCharsets.UTF_8));
            };

            log.info("Subscriber 已启动，等待消息 ...  (Ctrl+C 退出)");
            // 简单的持续 poll 循环
            while (!Thread.currentThread().isInterrupted()) {
                int n = subscription.poll(handler, 10);
                if (n == 0) {
                    Thread.yield();
                }
            }
        }
    }
}
