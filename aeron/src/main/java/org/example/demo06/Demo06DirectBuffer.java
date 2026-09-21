package org.example.demo06;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ===========================================================================
 * Demo06 —— DirectBuffer / Agrona：用二进制字段替代字符串
 * ===========================================================================
 *
 * 前几个 demo 都用字符串发消息，方便肉眼调试；
 * 但真实的低延迟系统绝不会把行情序列化成 JSON / 字符串再发。
 *
 * 本 demo 展示：
 *   * 用 UnsafeBuffer 直接写 long / double / int，无需分配任何对象
 *   * Publisher / Subscriber 使用同一份"字节布局约定"
 *   * 消息大小是固定的 32 字节，极致高效
 *
 * 消息布局约定（32 字节）：
 *   偏移 0  长度 8   long   timestampNanos
 *   偏移 8  长度 8   double bidPrice
 *   偏移 16 长度 8   double askPrice
 *   偏移 24 长度 4   int    volume
 *   偏移 28 长度 4   int    reserved
 *
 * 后续 demo07 会在此基础上引入 FragmentAssembler 处理大消息。
 */
public class Demo06DirectBuffer {

    private static final Logger log = LogManager.getLogger(Demo06DirectBuffer.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1006;

    // ============ 消息字段偏移量：Publisher/Subscriber 必须一致 ============
    private static final int OFFSET_TIMESTAMP = 0;
    private static final int OFFSET_BID       = 8;
    private static final int OFFSET_ASK       = 16;
    private static final int OFFSET_VOLUME    = 24;
    private static final int OFFSET_RESERVED  = 28;
    private static final int MSG_LENGTH       = 32;

    public static void main(String[] args) throws Exception {

        AtomicBoolean running = new AtomicBoolean(true);

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
                // FragmentHandler 里 buffer 已经是 DirectBuffer，直接读字段即可，零拷贝
                FragmentHandler handler = (DirectBuffer buffer, int offset, int length, io.aeron.logbuffer.Header header) -> {
                    // length 必然 == MSG_LENGTH（我们只发这种消息），做个 assert 检查
                    if (length != MSG_LENGTH) {
                        log.error("消息长度异常: {}", length);
                        return;
                    }
                    long ts    = buffer.getLong(offset + OFFSET_TIMESTAMP);
                    double bid = buffer.getDouble(offset + OFFSET_BID);
                    double ask = buffer.getDouble(offset + OFFSET_ASK);
                    int vol    = buffer.getInt(offset + OFFSET_VOLUME);
                    log.info("[Sub] ts={} bid={} ask={} vol={}",
                            ts, String.format("%.2f", bid), String.format("%.2f", ask), vol);
                };
                while (running.get()) {
                    if (subscription.poll(handler, 10) == 0) {
                        Thread.yield();
                    }
                }
            }, "binary-sub");
            subscriberThread.start();

            // -------- Publisher --------
            // 只分配一次 UnsafeBuffer：零 GC 是低延迟的核心
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MSG_LENGTH));

            for (int i = 0; i < 10; i++) {
                // 用二进制方式写字段
                buffer.putLong(OFFSET_TIMESTAMP, System.nanoTime());
                buffer.putDouble(OFFSET_BID,    65000.10 + i);
                buffer.putDouble(OFFSET_ASK,    65000.20 + i);
                buffer.putInt(OFFSET_VOLUME,    100 + i);
                buffer.putInt(OFFSET_RESERVED,  0);

                long r;
                while ((r = publication.offer(buffer, 0, MSG_LENGTH)) < 0) {
                    Thread.yield();
                }
                log.info("[Pub] 发送第 {} 条 (pos={})", i, r);
                Thread.sleep(100);
            }

            Thread.sleep(500);
            running.set(false);
            subscriberThread.join();

            log.info("Demo06 结束。整个链路没有任何 String 分配，只有 DirectBuffer + 固定偏移读写。");
        }
    }
}
