package org.example.demo03;

import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * ===========================================================================
 * Demo03c —— 独立进程运行 Publisher
 * ===========================================================================
 *
 * 前置：先启动
 *   1) Demo03aStandaloneMediaDriver
 *   2) Demo03bSubscriberProcess
 *
 * 本进程发送 10 条消息后即退出；Subscriber 进程会持续打印看到的消息。
 */
public class Demo03cPublisherProcess {

    private static final Logger log = LogManager.getLogger(Demo03cPublisherProcess.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1003;

    public static void main(String[] args) throws Exception {

        try (Aeron aeron = Aeron.connect(new Aeron.Context());
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID)) {

            // 等 Subscriber 已经在同一个 driver 上完成注册
            log.info("等待 Subscriber 连接 ...");
            while (!publication.isConnected()) {
                Thread.sleep(20);
            }
            log.info("Subscriber 已连接，开始发送");

            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));

            for (int i = 0; i < 10; i++) {
                String msg = "cross-jvm-tick-" + i;
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                buffer.putBytes(0, bytes);

                long r;
                while ((r = publication.offer(buffer, 0, bytes.length)) < 0) {
                    Thread.yield();
                }
                log.info("[Pub-Process] 已发送: {} (pos={})", msg, r);

                Thread.sleep(200);
            }

            log.info("Publisher 结束。");
        }
    }
}
