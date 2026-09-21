package org.example.demo08;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ===========================================================================
 * Demo08 —— UDP Unicast：跨机器（这里用回环 127.0.0.1 演示）
 * ===========================================================================
 *
 * 前面 demo 都用 aeron:ipc（共享内存）。真实跨机场景要用 aeron:udp。
 *
 * 单播 Channel URI 的两种写法（Publisher 和 Subscriber 用的字段略不同）：
 *   Publisher 侧 ：  aeron:udp?endpoint=<对端 host:port>
 *   Subscriber 侧：  aeron:udp?endpoint=<本端 host:port>
 *
 * "endpoint" 在 Aeron 里代表 "接收方绑定的地址"。
 * 也就是说 Subscriber 用 endpoint 指定"我在哪个 IP:port 监听"，
 * Publisher 用 endpoint 指定"我发到哪个 IP:port"。两边写的都是"接收方地址"。
 *
 * 本 demo 在同一台机器上用 127.0.0.1 演示，方便学习：
 *   Subscriber ：aeron:udp?endpoint=127.0.0.1:40456
 *   Publisher  ：aeron:udp?endpoint=127.0.0.1:40456
 *
 * 真到多机时：
 *   Machine B（订阅方，收方）：aeron:udp?endpoint=192.168.1.20:40456
 *   Machine A（发布方，发方）：aeron:udp?endpoint=192.168.1.20:40456
 *
 * 注意事项：
 *   1) 必须打开 UDP 端口（防火墙）
 *   2) UDP 不像 TCP 那样"握手"，所以 offer() 返回 NOT_CONNECTED 需要多试几次
 *   3) UDP 有 MTU 限制，超过 MTU 的消息会被 Aeron 自动分片（再看一次 demo07）
 */
public class Demo08UdpUnicast {

    private static final Logger log = LogManager.getLogger(Demo08UdpUnicast.class);

    // 收方端点（在本机演示，实机换成对方的 IP 即可）
    private static final String ENDPOINT = "127.0.0.1:40456";

    private static final String SUBSCRIBER_CHANNEL = "aeron:udp?endpoint=" + ENDPOINT;
    private static final String PUBLISHER_CHANNEL  = "aeron:udp?endpoint=" + ENDPOINT;
    private static final int STREAM_ID = 1008;

    public static void main(String[] args) throws Exception {

        AtomicBoolean running = new AtomicBoolean(true);

        // 显式配置 MediaDriver.Context：
        //   * ThreadingMode.SHARED_NETWORK 让 sender/receiver 共用一个网络线程，学习足够
        //   * dirDeleteOnStart 避免"上次没关干净"导致冲突
        MediaDriver.Context ctx = new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED_NETWORK)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        try (MediaDriver driver = MediaDriver.launchEmbedded(ctx);
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(driver.aeronDirectoryName()));
             Subscription subscription = aeron.addSubscription(SUBSCRIBER_CHANNEL, STREAM_ID);
             Publication publication = aeron.addPublication(PUBLISHER_CHANNEL, STREAM_ID)) {

            log.info("等待 UDP Publication/Subscription 建立连接 ...");
            // UDP 建链会比 IPC 稍慢
            while (!publication.isConnected() || !subscription.isConnected()) {
                Thread.sleep(20);
            }
            log.info("UDP 已连接: {}", ENDPOINT);

            // -------- Subscriber 线程 --------
            Thread subThread = new Thread(() -> {
                FragmentHandler handler = (buffer, offset, length, header) -> {
                    byte[] data = new byte[length];
                    buffer.getBytes(offset, data);
                    log.info("[UDP-Sub] 收到: {}", new String(data, StandardCharsets.UTF_8));
                };
                while (running.get()) {
                    if (subscription.poll(handler, 10) == 0) {
                        Thread.yield();
                    }
                }
            }, "udp-sub");
            subThread.start();

            // -------- Publisher --------
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
            for (int i = 0; i < 10; i++) {
                String msg = "udp-msg-" + i;
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                buffer.putBytes(0, bytes);

                long r;
                int retry = 0;
                while ((r = publication.offer(buffer, 0, bytes.length)) < 0) {
                    if (++retry > 1000) {
                        log.error("发送重试超上限：result={}", r);
                        break;
                    }
                    Thread.sleep(1);
                }
                log.info("[UDP-Pub] 已发送 {} (pos={})", msg, r);
                Thread.sleep(100);
            }

            Thread.sleep(500);
            running.set(false);
            subThread.join();

            log.info("Demo08 结束。把 ENDPOINT 换成真实 IP 即可跨机复现。");
        }
    }
}
