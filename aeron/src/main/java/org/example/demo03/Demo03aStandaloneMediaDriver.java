package org.example.demo03;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ===========================================================================
 * Demo03a —— 独立进程运行 MediaDriver
 * ===========================================================================
 *
 * 这个类"就是一个 MediaDriver 进程"。启动后不要退出。
 * 它相当于给整台机器提供一个"数据运输引擎"，之后其他 JVM（Publisher / Subscriber）
 * 都可以连过来，共用它做 IPC 通信。
 *
 * 生产环境中，Aeron 官方也建议：
 *   - MediaDriver 单独部署 / 独立 JVM 常驻
 *   - 多个业务进程作为 Aeron Client 接入
 *
 * 启动顺序（见 README.txt）：
 *   1. 本类     Demo03aStandaloneMediaDriver
 *   2. 订阅端  Demo03bSubscriberProcess
 *   3. 发送端  Demo03cPublisherProcess
 */
public class Demo03aStandaloneMediaDriver {

    private static final Logger log = LogManager.getLogger(Demo03aStandaloneMediaDriver.class);

    public static void main(String[] args) {

        // Context 用来定制 MediaDriver 的所有行为。
        // 这里只演示两项最关键的：
        //   1) ThreadingMode：SHARED / SHARED_NETWORK / DEDICATED
        //      - SHARED   ：所有 driver 内部任务共用 1 个线程（最省资源，适合学习/低负载）
        //      - DEDICATED：conductor / sender / receiver 各占一个线程（高性能生产配置）
        //   2) dirDeleteOnStart / dirDeleteOnShutdown：清理共享内存目录
        //      开发时打开可以避免"上次没关干净"导致的目录冲突
        MediaDriver.Context ctx = new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        // MediaDriver.launch() 会用当前 JVM 拉起 driver。
        // 注意与 launchEmbedded() 的区别：
        //   - launch()         ：使用固定的默认目录（同机器只能有一个 driver 实例）
        //   - launchEmbedded() ：为你生成一个随机目录，同机器可以并存多个 driver
        // 独立部署 MediaDriver 时通常用 launch()，让所有客户端知道去哪里找它。
        try (MediaDriver driver = MediaDriver.launch(ctx)) {

            log.info("======================================");
            log.info("MediaDriver 已启动");
            log.info("aeronDirectoryName = {}", driver.aeronDirectoryName());
            log.info("按 Ctrl+C 停止");
            log.info("======================================");

            // 阻塞当前线程，避免 main 退出导致 driver 关闭。
            // 优雅方案是注册 shutdown hook；这里为了简单直接 Thread.currentThread().join()
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
