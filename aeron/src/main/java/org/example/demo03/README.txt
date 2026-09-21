Demo03 —— 独立 JVM 运行 Publisher / Subscriber
=============================================================================

对应文档《01.aeron 零基础入门》Level 7 / V7：
  "独立 JVM"，把 Publisher 与 Subscriber 拆到两个进程，它们共享同一个 MediaDriver。

这个 demo 由三个 main 类组成，需要按下面顺序启动：

  1) 先启动 Demo03aStandaloneMediaDriver
     —— 独立进程运行 MediaDriver，会打印出 aeronDirectoryName
     —— 保持进程运行，不要退出

  2) 再启动 Demo03bSubscriberProcess
     —— 独立进程运行 Subscriber，连接第 1 步的 MediaDriver

  3) 再启动 Demo03cPublisherProcess
     —— 独立进程运行 Publisher，向 Subscriber 发送 10 条消息

预期看到：
  Subscriber 进程持续打印从 Publisher 收到的消息。

关键点：
  * 三个 JVM 通过 aeronDirectoryName（默认 /dev/shm 或 java.io.tmpdir 下的目录）共享内存
  * Publisher / Subscriber 都不再自己拉起 MediaDriver，而是"连接现有的"
  * 这是生产部署中最常见的形态：MediaDriver 单独部署，多个业务进程复用
