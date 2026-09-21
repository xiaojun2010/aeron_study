package org.example.demo07;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
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
 * Demo07 —— 大消息 & FragmentAssembler
 * ===========================================================================
 *
 * Aeron 的默认 MTU（Maximum Transmission Unit）通常在 1408 字节左右。
 * 一旦你的消息超过 MTU，Aeron 会自动"拆包"，Subscriber 端就必须把多个 fragment
 * 重新拼装成一条完整业务消息。
 *
 * 拼装工作官方已经封装好了：**FragmentAssembler**
 *   * 你把真正的业务 handler 交给 FragmentAssembler
 *   * FragmentAssembler 自己判断"这条消息是不是分片"，全部拼齐后再回调你
 *
 * 本 demo 发送 10 条随机长度（100~4096 字节）的消息，观察：
 *   * 直接注册裸 handler：会看到 length 变小，消息被"截断"（其实是分片）
 *   * 使用 FragmentAssembler 包装：始终能拿到完整的原始 payload
 *
 * 这是从 demo01~demo06 迈向"真实业务消息"必须过的一关。
 */
public class Demo07FragmentAssembler {

    private static final Logger log = LogManager.getLogger(Demo07FragmentAssembler.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1007;

    /** 最大消息长度：远超默认 MTU（1408），足以观察到拆包 */
    private static final int MAX_MSG = 4096;

    public static void main(String[] args) throws Exception {

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger assembledMessages = new AtomicInteger(0);
        AtomicInteger rawFragments = new AtomicInteger(0);

        try (MediaDriver mediaDriver = MediaDriver.launchEmbedded();
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            while (!publication.isConnected() || !subscription.isConnected()) {
                Thread.sleep(5);
            }

            // -------- 业务真正想要的 handler：接收"完整"的消息 --------
            FragmentHandler businessHandler = (buffer, offset, length, header) -> {
                assembledMessages.incrementAndGet();
                byte[] data = new byte[Math.min(length, 32)]; // 只打印前 32 字节
                buffer.getBytes(offset, data);
                log.info("[Assembled] 收到完整消息 length={}, 头部=\"{}...\"",
                        length, new String(data, StandardCharsets.UTF_8));
            };

            // -------- 用 FragmentAssembler 包装业务 handler --------
            // FragmentAssembler 内部会：
            //   * 首次看到大消息 → 分配一个"重组 buffer"缓存起来
            //   * 中间 fragment 到达 → 追加进 buffer
            //   * 最后一个 fragment 到达 → 一次性调用 businessHandler
            //   * 每个 session id 各自有一份重组 buffer，互不干扰
            FragmentHandler assembler = new FragmentAssembler(businessHandler);

            // -------- 同时装一个"裸 handler"仅统计片数，方便对比 --------
            // 注意：一个 Subscription 只应该注册一个 handler；这里通过第二个 subscription 观察
            Subscription rawSubscription = aeron.addSubscription(CHANNEL, STREAM_ID);
            FragmentHandler rawCounter = (buffer, offset, length, header) -> {
                rawFragments.incrementAndGet();
                // header.flags() 里包含 BEGIN/END fragment 标记：
                //   BEGIN_FRAG_FLAG = 0x80, END_FRAG_FLAG = 0x40
                // 完整消息（未分片）= BEGIN | END = 0xC0
                byte flags = header.flags();
                boolean begin = (flags & 0x80) != 0;
                boolean end   = (flags & 0x40) != 0;
                log.info("  [RawFragment] len={} begin={} end={}", length, begin, end);
            };

            Thread subThread = new Thread(() -> {
                while (running.get()) {
                    int n1 = subscription.poll(assembler, 20);
                    int n2 = rawSubscription.poll(rawCounter, 20);
                    if (n1 + n2 == 0) {
                        Thread.yield();
                    }
                }
            }, "assembler-sub");
            subThread.start();

            // -------- Publisher：发送不同长度的消息 --------
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MAX_MSG));
            int[] sizes = {100, 500, 1200, 1408, 1409, 2000, 3000, 4000, 4096, 800};

            for (int i = 0; i < sizes.length; i++) {
                int size = sizes[i];
                // 用可识别的字节填充：前缀标记序号 + 长度
                String prefix = String.format("MSG#%02d(len=%d) ", i, size);
                byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
                buffer.putBytes(0, prefixBytes);
                // 填充剩余部分为 'A'
                for (int j = prefixBytes.length; j < size; j++) {
                    buffer.putByte(j, (byte) 'A');
                }

                long r;
                while ((r = publication.offer(buffer, 0, size)) < 0) {
                    Thread.yield();
                }
                log.info("[Pub] 发送 {}(pos={})", prefix, r);
                Thread.sleep(50);
            }

            Thread.sleep(500);
            running.set(false);
            subThread.join();
            rawSubscription.close();

            log.info("========================================");
            log.info("使用 FragmentAssembler，业务收到完整消息数: {}", assembledMessages.get());
            log.info("未拼装时的裸 fragment 片数              : {}", rawFragments.get());
            log.info("可以看到 fragment 数 > 消息数，就是被拆包了");
            log.info("========================================");
        }
    }
}
