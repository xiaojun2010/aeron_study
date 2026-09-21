# 第 21 章 · Aeron Cluster：概述与 Raft

## 学习目标

- 理解 Aeron Cluster 的定位（复制状态机）；
- 掌握 Raft 三大要素在 Aeron Cluster 中的映射；
- 认识 Cluster 里的关键角色：Consensus Module / Clustered Service / Client / Archive；
- 明白 Cluster 与普通 Archive/Transport 的关系。

## 21.1 Cluster 是什么

**Aeron Cluster** 是基于 Aeron Transport + Archive 实现的 **Raft 复制状态机** 框架。它给你：

- **强一致的服务**：多副本，leader 挂了自动切换；
- **确定性执行**：所有节点执行相同的输入序列，得到相同状态；
- **崩溃恢复**：从本地 Archive replay 重放；
- **快照**：定期落地 state，避免每次都 replay 全部日志。

典型用途：
- 撮合引擎 core（一致性 > 吞吐）
- 订单管理系统
- 分布式配置中心（etcd 替代品）

## 21.2 Cluster != Kafka + ZK

不要把 Cluster 当"更快的 ZK"。它的正确心智模型是：

> **"用 Raft 让你写的 Clustered Service（一个纯业务对象）自动多副本、自动 fail-over、自动恢复。"**

你不需要关心 Raft 协议细节，只需要：
1. 用 SBE 定义输入/输出消息；
2. 实现 `ClusteredService` 接口，处理消息、维护状态；
3. Cluster 帮你复制、持久化、快照、fail-over。

## 21.3 Raft 三要素在 Cluster 中的映射

| Raft 概念 | Aeron Cluster |
|---|---|
| Log entries | Aeron Publication 消息（走 IPC/UDP，然后被 Archive 记录）|
| Leader election | Consensus Module 内部，用 votes stream |
| Log replication | Leader 用 MDC 把每条消息发给 followers；每个节点本地 Archive 都录一份 |
| Commit index | 由 quorum 确认的最大 position |
| State machine | 用户实现的 `ClusteredService` |
| Snapshot | Cluster 定期 ask service 生成 snapshot，落 Archive |

## 21.4 一个 Cluster 节点内部

```
┌────────────── Node ────────────────┐
│                                    │
│  ┌────────────────────────────┐    │
│  │  Consensus Module          │    │
│  │   (Raft, leader election)  │    │
│  └───────────┬────────────────┘    │
│              │                     │
│  ┌───────────▼────────────────┐    │
│  │ Clustered Service          │    │
│  │  (你的业务逻辑)             │    │
│  └───────────┬────────────────┘    │
│              │                     │
│  ┌───────────▼────────────────┐    │
│  │ Aeron Archive              │    │
│  │  (log + snapshot 落地)      │    │
│  └────────────────────────────┘    │
│                                    │
│  ┌────────────────────────────┐    │
│  │ Aeron MediaDriver          │    │
│  └────────────────────────────┘    │
└────────────────────────────────────┘
```

## 21.5 Cluster 拓扑

生产标配 3 或 5 节点：

```
   ┌────────┐        ┌────────┐        ┌────────┐
   │ Node A │  ◀───▶ │ Node B │ ◀───▶ │ Node C │
   │(Leader)│  MDC   │(Follower)MDC   │(Follower)
   └────┬───┘        └────────┘        └────────┘
        │
      client → 只跟 leader 说话
```

- 节点之间用 UDP MDC (Ch17) 通信；
- Client 连 Cluster 会自动找到 leader；
- 3 节点容忍 1 挂，5 节点容忍 2 挂。

## 21.6 Cluster 的四类 stream

Cluster 内部有一堆 stream，主要类别：

| Stream 类 | 用途 |
|---|---|
| Ingress | Client → Leader 的请求 |
| Log | Leader 复制到 followers 的日志 |
| Consensus | 节点间 Raft 消息（vote、append 等）|
| Egress | Leader → Clients 的响应 |
| Snapshot | Service 的 snapshot 数据 |

Cluster 会自动配这些 stream 和对应的 recording，业务不需要管。

## 21.7 Client 的行为

Cluster Client 提供高层 API：

```java
AeronCluster client = AeronCluster.connect(new AeronCluster.Context()
    .ingressChannel("aeron:udp")                              // 会自动追加 endpoint
    .ingressEndpoints("0=node-a:9010,1=node-b:9010,2=node-c:9010")
    .egressChannel("aeron:udp?endpoint=me:0"));

// 发送请求
UnsafeBuffer buf = new UnsafeBuffer(...);
client.offer(buf, 0, len);
```

client 会：
1. 尝试连接 leader；
2. 如果 leader 变了，自动重连；
3. 通过 egress channel 收响应。

## 21.8 Snapshot

Log 不能无限增长，Cluster 定期 ask service 出 snapshot：

```
Log:  msg1 msg2 msg3 [snap] msg4 msg5 [snap] msg6 ...

恢复流程：从最后一个 snapshot 加载 state，然后 replay snapshot 之后的 msg
```

Snapshot 由 service 自己生成（`onTakeSnapshot(ExclusivePublication)`），也由 service 自己加载（`onStart(Cluster, image)`）。

## 21.9 Cluster 版本 & 兼容性

- Cluster 与 Transport / Archive **必须版本一致**；
- 升级 Cluster 要滚动升级，先 followers 后 leader；
- Service 也要有 schemaVersion 兼容性设计（用 SBE 的 sinceVersion）。

## 21.10 学习路径

Ch22 会写一个完整的 Clustered Service demo：
- 三节点，本机不同端口
- 一个简单的计数器 service
- 崩溃一个节点看看会不会 fail-over
- Snapshot 演示

先在这一章建立心智模型：**Aeron Cluster = 你写状态机，Cluster 帮你 Raft 复制**。

## 21.11 与 etcd / Zookeeper 的对比

| 维度 | Aeron Cluster | etcd | ZK |
|---|---|---|---|
| 用法 | 嵌入到你的进程 | 独立集群 + client | 独立集群 + client |
| 协议 | Raft | Raft | Zab |
| 数据 | 你的 SBE 消息 | KV | KV |
| 延迟 | μs 级 | ms 级 | ms 级 |
| 吞吐 | 数百万/s | 数万/s | 数万/s |
| 适合 | 业务状态机 | 元数据 | 元数据 |

## 21.12 小结

- Aeron Cluster = Raft 复制状态机 + Aeron 传输栈
- 不是"更快的 ZK"，是"给你自己业务对象加 Raft"
- 三层协作：Consensus Module（Raft）+ Service（业务）+ Archive（持久化）
