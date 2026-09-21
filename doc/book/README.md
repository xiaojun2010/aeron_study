# Aeron 从入门到高级：完整学习教程

本书是给已经写过 Java（有 Netty/Kafka 使用经验）的工程师准备的 **Aeron 系统性学习手册**。
一共 25 章，8 大部分，从"最小 Hello World"讲到"生产级低延迟行情总线"。

配套代码在 `/Users/zxj/work/workspace/quant/aeron_study/aeron` 的 demo01 ~ demo10；
本书正文里出现的可运行片段大多可以直接放进对应 demo 包里跑起来。

## 阅读建议

- **零基础读者**：先读 `../01.aeron 零基础入门.MD`，再顺序读本书 Part I~II，配合 demo01~demo05
- **有 Kafka 背景**：留意 Ch01 中的语义对比表；不要把 Aeron 当 "更快的 Kafka" 使用
- **准备接入行情/交易系统**：Part III + Part VIII 是重点
- **需要复制状态机**：Part VII

## 目录

### 第一部分 · 基础
- [第 01 章 · 为什么选择 Aeron？消息模型对比](./ch01-why-aeron.md)
- [第 02 章 · 六个核心概念快览](./ch02-core-concepts.md)
- [第 03 章 · 环境准备与第一个可运行程序](./ch03-first-program.md)

### 第二部分 · Transport 基础
- [第 04 章 · MediaDriver：从嵌入式到独立部署](./ch04-media-driver.md)
- [第 05 章 · Publication 全解](./ch05-publication.md)
- [第 06 章 · Subscription 与消费模型](./ch06-subscription.md)
- [第 07 章 · Channel URI 完全指南](./ch07-channel-uri.md)
- [第 08 章 · offer 返回值与 Back Pressure](./ch08-offer-backpressure.md)
- [第 09 章 · IdleStrategy 与低延迟循环设计](./ch09-idle-strategy.md)

### 第三部分 · 编解码与消息
- [第 10 章 · Agrona DirectBuffer 与零 GC 编程](./ch10-agrona-buffer.md)
- [第 11 章 · 大消息与 FragmentAssembler](./ch11-fragment-assembler.md)
- [第 12 章 · Simple Binary Encoding（SBE）](./ch12-sbe.md)

### 第四部分 · 内部机制
- [第 13 章 · Image / Session / Term Buffer](./ch13-image-term.md)
- [第 14 章 · 流控、拥塞控制与 Loss Recovery](./ch14-flow-control.md)
- [第 15 章 · Counters 与诊断](./ch15-counters.md)

### 第五部分 · 高级传输模式
- [第 16 章 · UDP Multicast](./ch16-multicast.md)
- [第 17 章 · Multi-Destination Cast（MDC）](./ch17-mdc.md)
- [第 18 章 · Request / Response 模式](./ch18-req-resp.md)

### 第六部分 · Aeron Archive
- [第 19 章 · Archive 概览与录制](./ch19-archive-basics.md)
- [第 20 章 · Replay / Replay-Merge / Live Merge](./ch20-replay-merge.md)

### 第七部分 · Aeron Cluster
- [第 21 章 · Cluster 概述与 Raft](./ch21-cluster-overview.md)
- [第 22 章 · 编写一个 Clustered Service](./ch22-clustered-service.md)

### 第八部分 · 生产化与实战
- [第 23 章 · 部署、监控、性能调优](./ch23-deploy-tuning.md)
- [第 24 章 · 常见错误与排错手册](./ch24-troubleshooting.md)
- [第 25 章 · 综合案例：低延迟行情总线](./ch25-market-bus.md)

## 参考资料

- Aeron 官方仓库：<https://github.com/aeron-io/aeron>
- 官方 Cookbook 代码：<https://github.com/aeron-io/aeron-cookbook-code>
- Aeron 官方文档站：<https://aeron.io/docs/>
- 论文 *Mechanical Sympathy* 系列（Martin Thompson）

## 约定

- 代码块中的 Java 版本 = 21（与本仓库 pom 保持一致）
- Aeron 版本 = 1.44.1（Ch01 会解释这个选择）
- 所有示例日志走 log4j2，配置见 `aeron/src/main/resources/log4j2.xml`
