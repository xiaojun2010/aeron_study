package org.example.demo10;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===========================================================================
 * Demo10 —— 简化版行情总线（综合示例，对齐文档 "V10" 的雏形）
 * ===========================================================================
 *
 * 把前 9 个 demo 学到的东西融合到一起：
 *
 *   ┌────────────┐   Aeron IPC (stream=1010)   ┌─────────────┐
 *   │ MarketData │ ─────────────────────────>  │ Strategy    │
 *   │ Collector  │                             │ Engine      │
 *   │ (模拟 WS)  │                             ├─────────────┤
 *   └────────────┘                             │ Recorder    │
 *                                              └─────────────┘
 *
 * 用到的知识点：
 *   - Embedded MediaDriver          (demo01)
 *   - 独立线程 Pub/Sub               (demo02)
 *   - offer() 返回值全处理           (demo04)
 *   - BackoffIdleStrategy           (demo05)
 *   - DirectBuffer 二进制布局         (demo06)
 *   - FragmentAssembler             (demo07)
 *   - 多 Subscriber 广播             (demo09)
 *
 * 消息布局（固定 40 字节 + 变长 symbol 字符串，共 <= 64 字节）：
 *   0  长度 8   long   timestampNanos
 *   8  长度 8   double bidPrice
 *   16 长度 8   double askPrice
 *   24 长度 4   int    volume
 *   28 长度 4   int    symbolLength
 *   32 长度 N   bytes  symbol (UTF-8, <= 32)
 *
 * 你可以在真实系统里把 "Collector" 换成 Netty WebSocket + Binance，
 * 把 "Strategy" 换成真正的策略引擎，即得到文档里描述的 V10 架构。
 */
public class Demo10MarketDataBus {

    private static final Logger log = LogManager.getLogger(Demo10MarketDataBus.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1010;

    // 消息偏移
    private static final int OFF_TS       = 0;
    private static final int OFF_BID      = 8;
    private static final int OFF_ASK      = 16;
    private static final int OFF_VOL      = 24;
    private static final int OFF_SYM_LEN  = 28;
    private static final int OFF_SYM_DATA = 32;

    private static final int MSG_COUNT = 1_000;

    public static void main(String[] args) throws Exception {

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong strategyCount = new AtomicLong(0);
        AtomicLong recorderCount = new AtomicLong(0);

        try (MediaDriver driver = MediaDriver.launchEmbedded();
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(driver.aeronDirectoryName()));
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             Subscription subStrategy = aeron.addSubscription(CHANNEL, STREAM_ID);
             Subscription subRecorder = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            while (!publication.isConnected()
                    || !subStrategy.isConnected()
                    || !subRecorder.isConnected()) {
                Thread.sleep(10);
            }
            log.info("行情总线就绪，Publication + 2 Subscription 已连接");

            // -------- 消费者线程：Strategy --------
            Thread tStrategy = new Thread(() -> runConsumer(
                    "Strategy", subStrategy, running, strategyCount, /*doWork=*/true), "strategy");
            // -------- 消费者线程：Recorder --------
            Thread tRecorder = new Thread(() -> runConsumer(
                    "Recorder", subRecorder, running, recorderCount, /*doWork=*/false), "recorder");
            tStrategy.start();
            tRecorder.start();

            // -------- 生产者：模拟 Collector --------
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
            long backPressureHits = 0;
            String[] symbols = {"BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"};

            long start = System.nanoTime();
            for (int i = 0; i < MSG_COUNT; i++) {
                String symbol = symbols[i % symbols.length];
                byte[] symBytes = symbol.getBytes(StandardCharsets.UTF_8);
                double mid = 65000 + ThreadLocalRandom.current().nextGaussian() * 5;

                buffer.putLong(OFF_TS,   System.nanoTime());
                buffer.putDouble(OFF_BID, mid - 0.05);
                buffer.putDouble(OFF_ASK, mid + 0.05);
                buffer.putInt(OFF_VOL,    ThreadLocalRandom.current().nextInt(1, 500));
                buffer.putInt(OFF_SYM_LEN, symBytes.length);
                buffer.putBytes(OFF_SYM_DATA, symBytes);
                int msgLen = OFF_SYM_DATA + symBytes.length;

                // 处理 offer 返回值：核心 4 种负值
                long r;
                while (true) {
                    r = publication.offer(buffer, 0, msgLen);
                    if (r >= 0) break;
                    if (r == Publication.BACK_PRESSURED) {
                        backPressureHits++;
                        Thread.yield();
                    } else if (r == Publication.ADMIN_ACTION) {
                        // 立即重试
                    } else if (r == Publication.NOT_CONNECTED) {
                        Thread.sleep(1);
                    } else {
                        // CLOSED / MAX_POSITION_EXCEEDED
                        log.error("Publication 出现致命错误 result={}", r);
                        running.set(false);
                        tStrategy.join();
                        tRecorder.join();
                        return;
                    }
                }
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("[Collector] 发送 {} 条完成，用时 {} ms, backPressure={}",
                    MSG_COUNT, elapsedMs, backPressureHits);

            // 等两路 Subscriber 都消费完
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && (strategyCount.get() < MSG_COUNT || recorderCount.get() < MSG_COUNT)) {
                Thread.sleep(20);
            }

            running.set(false);
            tStrategy.join();
            tRecorder.join();

            log.info("========================================");
            log.info("Strategy 收到 : {}", strategyCount.get());
            log.info("Recorder 收到 : {}", recorderCount.get());
            log.info("========================================");
            log.info("这就是文档里描述的 V10 雏形：");
            log.info("  Collector 只管把行情打进 aeron:ipc；");
            log.info("  Strategy / Recorder 各自独立、互不干扰地消费；");
            log.info("  当消费者跟不上，Collector 会拿到 BACK_PRESSURED，业务再决定丢/等/降采样。");
        }
    }

    private static void runConsumer(String name,
                                    Subscription subscription,
                                    AtomicBoolean running,
                                    AtomicLong counter,
                                    boolean doWork) {
        // 生产环境常用的 IdleStrategy：先自旋、再 yield、再 park
        IdleStrategy idle = new BackoffIdleStrategy(
                100, 10,
                TimeUnit.MICROSECONDS.toNanos(1),
                TimeUnit.MICROSECONDS.toNanos(100));

        FragmentHandler handler = (buffer, offset, length, header) -> {
            long ts    = buffer.getLong(offset + OFF_TS);
            double bid = buffer.getDouble(offset + OFF_BID);
            double ask = buffer.getDouble(offset + OFF_ASK);
            int vol    = buffer.getInt(offset + OFF_VOL);
            int symLen = buffer.getInt(offset + OFF_SYM_LEN);
            byte[] sym = new byte[symLen];
            buffer.getBytes(offset + OFF_SYM_DATA, sym);
            String symbol = new String(sym, StandardCharsets.UTF_8);

            long n = counter.incrementAndGet();

            if (doWork) {
                // 模拟策略做一点点计算（生产里可能是撮合、信号计算等）
                double spread = ask - bid;
                if (n % 200 == 0) {
                    log.info("[{}] #{} {} bid={} ask={} spread={} vol={} (lat={}us)",
                            name, n, symbol,
                            String.format("%.4f", bid),
                            String.format("%.4f", ask),
                            String.format("%.4f", spread),
                            vol,
                            (System.nanoTime() - ts) / 1_000);
                }
            } else {
                // 模拟 Recorder：只落地不算逻辑
                if (n % 200 == 0) {
                    log.info("[{}] 已录制 {} 条，最新 symbol={}", name, n, symbol);
                }
            }
        };

        // FragmentAssembler 保证即使日后消息长度超过 MTU，业务代码也不用改
        FragmentHandler assembler = new FragmentAssembler(handler);

        while (running.get()) {
            int fragments = subscription.poll(assembler, 20);
            idle.idle(fragments);
        }
    }
}
