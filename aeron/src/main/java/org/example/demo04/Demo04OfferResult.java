package org.example.demo04;

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
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===========================================================================
 * Demo04 —— offer() 返回值 & Back Pressure 演示
 * ===========================================================================
 *
 * 对应文档《01.aeron 零基础入门》第 8/9 节：
 *   * offer() 的失败不是异常，返回值必须按语义处理
 *   * BACK_PRESSURED 是 Aeron 学习中第一个真正重要的坎
 *
 * 本 demo 的实验思路：
 *   Publisher 以极高速率发送
 *   Subscriber 故意"处理很慢"（每条消息 sleep 一下）
 *   于是 term buffer 很快被写满，Publisher 就会拿到 BACK_PRESSURED
 *
 * 你会在日志中看到大量：
 *   "offer BACK_PRESSURED, 已重试 xxx 次"
 * 这就是"消费者跟不上"的真实感觉。
 */
public class Demo04OfferResult {

    private static final Logger log = LogManager.getLogger(Demo04OfferResult.class);

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1004;

    public static void main(String[] args) throws Exception {

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong received = new AtomicLong(0);

        try (MediaDriver mediaDriver = MediaDriver.launchEmbedded();
             Aeron aeron = Aeron.connect(new Aeron.Context()
                     .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
             Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID)) {

            while (!publication.isConnected() || !subscription.isConnected()) {
                Thread.sleep(10);
            }

            // ---------------- 慢速 Subscriber ----------------
            // 每处理一条消息都 sleep 1ms，模拟"下游算法很慢"的情况。
            // 这样 Publisher 高速发送时就会打满 term buffer，触发 BACK_PRESSURED。
            Thread subscriberThread = new Thread(() -> {
                FragmentHandler handler = (buffer, offset, length, header) -> {
                    long n = received.incrementAndGet();
                    if (n % 100 == 0) {
                        byte[] data = new byte[length];
                        buffer.getBytes(offset, data);
                        log.info("[Sub-Slow] 已消费 {}，示例内容: {}",
                                n, new String(data, StandardCharsets.UTF_8));
                    }
                    try {
                        Thread.sleep(1); // 故意慢
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                while (running.get()) {
                    int fragments = subscription.poll(handler, 10);
                    if (fragments == 0) {
                        Thread.yield();
                    }
                }
            }, "slow-subscriber");
            subscriberThread.start();

            // ---------------- 高速 Publisher ----------------
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
            long backPressuredCount = 0;
            long notConnectedCount = 0;
            long adminActionCount = 0;
            long sent = 0;

            // 发送 2000 条，观察 BACK_PRESSURED 出现的规律
            for (int i = 0; i < 2000; i++) {
                String msg = "fast-msg-" + i;
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                buffer.putBytes(0, bytes);

                // -----------------------------------------------------------
                // 关键：处理 offer() 的返回值
                // -----------------------------------------------------------
                // Aeron 的 Publication 定义了几个"负值常量"：
                //   Publication.NOT_CONNECTED         = -1
                //   Publication.BACK_PRESSURED        = -2
                //   Publication.ADMIN_ACTION          = -3
                //   Publication.CLOSED                = -4
                //   Publication.MAX_POSITION_EXCEEDED = -5
                //
                // 生产做法：不同错误采取不同策略，而不是无脑循环
                //   - NOT_CONNECTED  ：Subscriber 还没连上，短暂等一下，或直接丢弃
                //   - BACK_PRESSURED ：消费者跟不上，业务上要决定"是丢是等"
                //   - ADMIN_ACTION   ：driver 内部在做管理动作（term rotate 等），可立即重试
                //   - CLOSED         ：Publication 已关闭，必须停止
                //   - MAX_POSITION_EXCEEDED : 流已耗尽，通常需要重建 Publication
                long result;
                int retries = 0;
                while (true) {
                    result = publication.offer(buffer, 0, bytes.length);
                    if (result >= 0) {
                        break; // 成功
                    }
                    if (result == Publication.NOT_CONNECTED) {
                        notConnectedCount++;
                        // 等一小会儿再试
                        Thread.sleep(1);
                    } else if (result == Publication.BACK_PRESSURED) {
                        backPressuredCount++;
                        // 消费者跟不上：这里演示"死等"策略
                        // 真实低延迟场景更可能："丢弃老数据 / 合并 / 降采样"
                        Thread.yield();
                    } else if (result == Publication.ADMIN_ACTION) {
                        adminActionCount++;
                        // 立即重试即可
                    } else if (result == Publication.CLOSED) {
                        log.error("Publication 已关闭，退出");
                        running.set(false);
                        subscriberThread.join();
                        return;
                    } else if (result == Publication.MAX_POSITION_EXCEEDED) {
                        log.error("流位置耗尽，需要新建 Publication");
                        running.set(false);
                        subscriberThread.join();
                        return;
                    }
                    retries++;
                    if (retries > 0 && retries % 10_000 == 0) {
                        log.warn("[Pub-Fast] 第 {} 条消息已重试 {} 次", i, retries);
                    }
                }
                sent++;
            }

            // 让 Subscriber 把剩余的消息消费掉
            log.info("发送完成，等待 Subscriber 消费剩余消息 ...");
            while (received.get() < sent) {
                Thread.sleep(50);
            }

            running.set(false);
            subscriberThread.join();

            // ------------ 统计输出 ------------
            log.info("========================================");
            log.info("发送成功           : {}", sent);
            log.info("收到               : {}", received.get());
            log.info("BACK_PRESSURED 次数: {}", backPressuredCount);
            log.info("NOT_CONNECTED 次数 : {}", notConnectedCount);
            log.info("ADMIN_ACTION 次数  : {}", adminActionCount);
            log.info("========================================");
            log.info("结论：即使消费者慢很多，Aeron 也不会 OOM，而是通过 BACK_PRESSURED");
            log.info("      把压力反馈到 Publisher。生产系统必须在这里做业务决策。");
        }
    }
}
