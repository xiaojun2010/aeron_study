package org.example.demo01;

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

/**
 * ===========================================================================
 * Demo01 —— Aeron 世界的 "Hello World"
 * ===========================================================================
 *
 * 对应文档《01.aeron 零基础入门》第 6 节 "第一个 Aeron 程序" 中的最小闭环：
 *
 *      Publisher ──> aeron:ipc ──> Subscriber
 *      （同一个 JVM，Embedded MediaDriver，把网络问题全部排除掉）
 *
 * 目标：
 *   1. 明白 Aeron 的六个核心概念：
 *      MediaDriver / Aeron Client / Channel / Stream ID / Publication / Subscription
 *   2. 学会最基本的 offer() 发送 与 poll() 接收
 *   3. 亲手看到一条字符串从 Publisher 流到 Subscriber
 *
 * 运行方式：直接右键 main 执行即可，无需额外部署 MediaDriver。
 */
public class Demo01HelloAeron {

    private static final Logger log = LogManager.getLogger(Demo01HelloAeron.class);

    /**
     * Channel：Aeron 使用 URI 描述"运输通道"
     *   - "aeron:ipc"  = 同一台机器、同一台 MediaDriver 下的进程/线程间通信（共享内存）
     *   - "aeron:udp?endpoint=host:port" = 跨机器的 UDP 通信
     * 目前 Demo01 只关心最简单的 IPC。
     */
    private static final String CHANNEL = "aeron:ipc";

    /**
     * Stream ID：同一个 Channel 里可以承载多条逻辑数据流，用 int 区分。
     * 可以（暂时）粗略类比成 Kafka 的 topic，但注意：Aeron 里没有"分区"的概念。
     */
    private static final int STREAM_ID = 1001;

    public static void main(String[] args) throws Exception {

        // -------------------------------------------------------------------
        // Step 1. 启动 Embedded MediaDriver
        // -------------------------------------------------------------------
        // MediaDriver 是 Aeron 的"数据运输引擎"，负责真正的底层传输（共享内存 / UDP）。
        // launchEmbedded() 会在当前 JVM 内启一个 driver 线程，并生成一个临时目录用于共享内存文件。
        // 生产环境通常会独立部署一个 MediaDriver 进程（见 demo03），Demo01 图方便用嵌入式。
        try (MediaDriver mediaDriver = MediaDriver.launchEmbedded();
             // ---------------------------------------------------------------
             // Step 2. 创建 Aeron 客户端
             // ---------------------------------------------------------------
             // Aeron.connect() 就是 Java 应用与 MediaDriver 通信的入口。
             // 必须把 aeronDirectoryName 指向刚才 MediaDriver 分配的目录，
             // 否则 client 会连不到 driver（会一直卡在 connect）。
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(mediaDriver.aeronDirectoryName()));

             // ---------------------------------------------------------------
             // Step 3. 创建 Publication（发送端）
             // ---------------------------------------------------------------
             // Channel + Stream ID 共同决定"这条通道上的这条流"。
             // Publication 的作用类似 Producer，但记住：offer() 不是 send()，返回值必须处理。
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);

             // ---------------------------------------------------------------
             // Step 4. 创建 Subscription（接收端）
             // ---------------------------------------------------------------
             // 与 Publication 使用完全相同的 CHANNEL + STREAM_ID，才能收到对方消息。
             Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            // -------------------------------------------------------------------
            // Step 5. 等待 Publication / Subscription "连接完成"
            // -------------------------------------------------------------------
            // Aeron 的 connect 是异步的，MediaDriver 需要时间在双方之间打通 image。
            // 如果你不等，直接 offer()，很可能返回 NOT_CONNECTED(-1)。
            log.info("等待 Publication 与 Subscription 建立连接 ...");
            while (!publication.isConnected() || !subscription.isConnected()) {
                Thread.sleep(10);
            }
            log.info("Publication / Subscription 已连接：{} streamId={}", CHANNEL, STREAM_ID);

            // -------------------------------------------------------------------
            // Step 6. 准备一个 DirectBuffer（Aeron 的核心：off-heap 直接内存）
            // -------------------------------------------------------------------
            // Aeron 不接受 String / byte[]，只接受 DirectBuffer。
            // UnsafeBuffer 是 Agrona 提供的高性能 DirectBuffer 实现，Aeron 已经把它作为传染依赖引入。
            // 256 字节对本 demo 已经绰绰有余。
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

            // -------------------------------------------------------------------
            // Step 7. FragmentHandler —— Subscriber 拿到一条消息后如何处理
            // -------------------------------------------------------------------
            // Aeron 会把消息以 "fragment" 的形式回调给我们（大消息可能被拆成多个 fragment，
            // 后面 demo 里会讨论 Assembler，Demo01 消息很短，一个 fragment 就是一条完整消息）。
            //   buffer/offset/length：消息在 buffer 中的位置
            //   header：Aeron 附带的元信息（session id、position 等），暂时忽略
            FragmentHandler fragmentHandler = (dirBuf, offset, length, header) -> {
                byte[] data = new byte[length];
                dirBuf.getBytes(offset, data);
                log.info("[Subscriber] 收到消息：{}", new String(data, StandardCharsets.UTF_8));
            };

            // -------------------------------------------------------------------
            // Step 8. 循环发送 5 条消息，同时不断 poll 拉取
            // -------------------------------------------------------------------
            for (int i = 0; i < 5; i++) {

                String msg = "hello-aeron-" + i;
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);

                // 把 bytes 写入 DirectBuffer 的 0 偏移处
                buffer.putBytes(0, bytes);

                // 关键 API：offer(buffer, offset, length)
                //   返回值 > 0 : 发送成功（返回新的 stream position）
                //   返回值 < 0 : 各种失败：
                //     -1 NOT_CONNECTED         订阅者还未连上
                //     -2 BACK_PRESSURED        订阅者跟不上，被反压了
                //     -3 ADMIN_ACTION          driver 正在做管理操作，可重试
                //     -4 CLOSED                Publication 已关闭
                //     -5 MAX_POSITION_EXCEEDED 触达 term buffer 上限
                // Demo01 只做最简单的重试，遇到负值就 yield 一下再试。
                long result;
                while ((result = publication.offer(buffer, 0, bytes.length)) < 0) {
                    // 生产环境应根据 result 具体值判断处理策略（后面 demo04 会详细讲）
                    Thread.yield();
                }
                log.info("[Publisher] 已发送：{} (position={})", msg, result);

                // 每次发一条就立刻 poll 一次，把消息取走
                //   poll(handler, fragmentLimit)
                //     fragmentLimit：本次最多处理多少个 fragment，控制 poll 的耗时上限
                //     返回值：实际处理了几个 fragment（0 表示没消息）
                // Aeron 的核心哲学：Subscriber 必须主动 poll，没有 callback。
                subscription.poll(fragmentHandler, 10);
            }

            // 最后再多 poll 几次，确保没漏掉尾部消息
            for (int i = 0; i < 10; i++) {
                subscription.poll(fragmentHandler, 10);
                Thread.sleep(10);
            }

            log.info("Demo01 结束。");
        }
        // try-with-resources 会依次关掉 Subscription / Publication / Aeron / MediaDriver。
    }
}
