# 第 22 章 · 编写一个 Clustered Service

## 学习目标

- 会实现 `ClusteredService` 接口的四个核心方法；
- 会在单机拉起 3 节点的 Cluster；
- 会写 Client 发送请求并收响应；
- 学会用 SBE 定义 ingress / egress message；
- 明白 Snapshot 的写与读。

## 22.1 依赖

```xml
<dependency>
    <groupId>io.aeron</groupId>
    <artifactId>aeron-cluster</artifactId>
    <version>1.44.1</version>
</dependency>
```

`aeron-cluster` 自动传染 `aeron-archive`、`aeron-driver` 等。

## 22.2 最小 ClusteredService

我们实现一个计数器：每收到一个"INC"请求，counter++，回复当前值。

```java
public class CounterService implements ClusteredService {

    private long counter;
    private final MutableDirectBuffer respBuf = new UnsafeBuffer(new byte[8]);

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        if (snapshotImage != null) {
            // 从 snapshot 恢复
            while (snapshotImage.poll((buf, o, l, h) -> counter = buf.getLong(o), 1) == 0) {
                Thread.yield();
            }
        }
    }

    @Override
    public void onSessionMessage(
        ClientSession session, long timestamp,
        DirectBuffer buffer, int offset, int length, Header header) {

        counter++;
        respBuf.putLong(0, counter);
        while (session.offer(respBuf, 0, 8) < 0) {
            // BackPressure 处理
        }
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        UnsafeBuffer buf = new UnsafeBuffer(new byte[8]);
        buf.putLong(0, counter);
        while (snapshotPublication.offer(buf, 0, 8) < 0) Thread.yield();
    }

    @Override public void onSessionOpen(ClientSession s, long ts) {}
    @Override public void onSessionClose(ClientSession s, long ts, CloseReason r) {}
    @Override public void onTimerEvent(long correlationId, long timestamp) {}
    @Override public void onRoleChange(Cluster.Role newRole) {}
    @Override public void onTerminate(Cluster cluster) {}
}
```

关键点：
- `onStart` 处理"从 snapshot 恢复" or "从头开始"；
- `onSessionMessage` 是核心业务逻辑；
- `onTakeSnapshot` 定期被 Cluster 调用，把 state dump 出来。

## 22.3 启动一个节点

```java
public static void runNode(int nodeId, String clusterHostList) {
    File baseDir = new File("/tmp/cluster-" + nodeId);

    MediaDriver.Context driverCtx = new MediaDriver.Context()
        .aeronDirectoryName(baseDir + "/driver")
        .threadingMode(ThreadingMode.SHARED);

    Archive.Context archiveCtx = new Archive.Context()
        .archiveDir(new File(baseDir, "archive"))
        .controlChannel("aeron:udp?endpoint=localhost:801" + nodeId)
        .replicationChannel("aeron:udp?endpoint=localhost:0");

    ConsensusModule.Context consensusCtx = new ConsensusModule.Context()
        .clusterMemberId(nodeId)
        .clusterMembers(clusterHostList)                  // "0=host0:2000...:2001..."
        .clusterDir(new File(baseDir, "cluster"))
        .ingressChannel("aeron:udp");

    ClusteredServiceContainer.Context serviceCtx = new ClusteredServiceContainer.Context()
        .clusteredService(new CounterService())
        .clusterDir(new File(baseDir, "cluster"));

    // 一起启动
    try (ClusteredMediaDriver clusteredDriver = ClusteredMediaDriver.launch(
             driverCtx, archiveCtx, consensusCtx);
         ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceCtx)) {
        Thread.currentThread().join();
    }
}
```

`clusterMembers` 字符串格式（3 节点）：

```
0=localhost:20000,localhost:20001,localhost:20002,localhost:20003,localhost:8000|
1=localhost:20010,localhost:20011,localhost:20012,localhost:20013,localhost:8001|
2=localhost:20020,localhost:20021,localhost:20022,localhost:20023,localhost:8002
```

每个成员 5 个端口：ingress / consensus / log / snapshot / archive-control（具体版本可能有小差异）。

## 22.4 启动集群（本机 3 节点）

```
Terminal 1: java CounterCluster 0 <memberList>
Terminal 2: java CounterCluster 1 <memberList>
Terminal 3: java CounterCluster 2 <memberList>
```

启动后：
- 3 节点先做 Raft 选举
- 其中一个变成 leader，另两个 follower
- 日志里能看到 "role=LEADER" / "role=FOLLOWER"

## 22.5 Client

```java
AeronCluster client = AeronCluster.connect(new AeronCluster.Context()
    .aeron(aeron)
    .ingressChannel("aeron:udp")
    .ingressEndpoints("0=localhost:20000,1=localhost:20010,2=localhost:20020")
    .egressChannel("aeron:udp?endpoint=localhost:0")
    .egressListener((clusterSessionId, timestamp, buffer, offset, length, header) -> {
        long newCounter = buffer.getLong(offset);
        log.info("counter = {}", newCounter);
    }));

UnsafeBuffer req = new UnsafeBuffer(new byte[4]);
req.putInt(0, 1); // INC 指令 code
for (int i = 0; i < 5; i++) {
    while (client.offer(req, 0, 4) < 0) Thread.yield();
    client.pollEgress();
    Thread.sleep(200);
}
```

预期输出：`counter = 1`, `counter = 2`, ... `counter = 5`。

## 22.6 演示 fail-over

- 找到 leader（日志里有 role=LEADER 的进程）
- 直接 kill 掉
- 剩下两个节点 quorum 依然满足，重新选主（几秒内）
- Client 会短暂断开，然后自动切到新 leader，继续 offer

## 22.7 Snapshot 触发

- 手动：`archive` 客户端调用 `takeSnapshot`；
- 自动：写多少条日志之后自动 snapshot（Cluster config）；
- Log 会 truncate 到 snapshot 之后，磁盘占用受控。

## 22.8 常见陷阱

1. **member list 端口错开忘了改**：3 节点会互相打不通。
2. **忘记 `aeronDirectoryName` 每节点独立**：三节点抢一个 driver 目录 → 崩。
3. **onSessionMessage 里做 IO**：会阻塞 Raft，慎重；有异步需求走 Ingress → 内部队列 → 单独线程。
4. **Service 不 deterministic**：使用 `Random`、System.currentTimeMillis() → 各节点状态发散，Raft 崩溃。**必须用 `cluster.time()` / `cluster.timeUnit()`。**
5. **snapshot 拉大**：state 上 GB 时要用 chunked snapshot（多 fragment）。

## 22.9 确定性执行的重要性

Cluster 要求每个节点执行输入序列后得到 **完全相同** 的状态：

| 允许 | 禁止 |
|---|---|
| `cluster.time()` | `System.currentTimeMillis()` |
| 事件驱动 timer（`cluster.scheduleTimer`） | `ScheduledExecutorService` |
| 请求里带的随机数 | `Math.random()` / `SecureRandom` |
| 请求里带的外部数据 | 内部读文件、发 HTTP |

## 22.10 读 aeron-cookbook

`aeron-cookbook-code` 里的 `cluster` 章节最完整，包含：
- 基础 cluster
- cluster + SBE 协议
- cluster + protocol-buffers
- Kotlin cluster

学到这里，去克隆下来跑一下：

```bash
git clone https://github.com/aeron-io/aeron-cookbook-code.git
cd aeron-cookbook-code/aeron-cluster/simple-cluster
./gradlew run
```

## 22.11 小结

- ClusteredService = 你的业务对象；Cluster 帮你 Raft
- Client 用 AeronCluster 与 leader 通信，自动切换
- 关键约束：**onSessionMessage 必须确定性**
- Snapshot 让 log 不无限增长
