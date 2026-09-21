# 第 06 章 · Subscription 与消费模型

## 学习目标

- 弄清 `Subscription`, `Image`, `FragmentHandler`, `ControlledFragmentHandler` 的关系；
- 掌握 `poll` / `controlledPoll` / `blockPoll` 三种消费方式；
- 学会用 `Image.position` 和 `boundedPoll` 做"回压 / 限速"；
- 明白多个 Publisher 打进同一 Subscription 时的语义。

## 6.1 Subscription 是"多 Image 的聚合"

```
Publisher A (sessionId=0x1a) ──┐
                               │
Publisher B (sessionId=0x2b) ──┼──▶ Subscription (channel="aeron:ipc", streamId=42)
                               │
Publisher C (sessionId=0x3c) ──┘

Subscription.images() = [ImageA, ImageB, ImageC]
```

- 每个 Publisher → 一个 sessionId → 在 Subscription 侧变成一个 Image；
- Image 是"某个会话的收方镜像"，各自有独立的 position / term buffer；
- `Subscription.poll()` 会轮询它拥有的所有 Image。

## 6.2 poll 的三种口味

```java
// 1) 最常用：每个 fragment 都回调
int poll(FragmentHandler handler, int fragmentLimit);

// 2) 允许 handler 返回 Action 控制流程
int controlledPoll(ControlledFragmentHandler handler, int fragmentLimit);

// 3) 阻塞式：一次拿一片连续内存（不是完整消息，只是连续字节）
int blockPoll(BlockHandler handler, int blockLengthLimit);
```

### 6.2.1 FragmentHandler（普通）

```java
FragmentHandler handler = (buffer, offset, length, header) -> {
    long pos = header.position();
    // 处理 length 字节
};
```

### 6.2.2 ControlledFragmentHandler

回调可以返回四种 Action：

| Action | 含义 |
|---|---|
| `CONTINUE` | 处理完，继续下一条 |
| `BREAK` | 立即停止本次 poll，下次 poll 从**这条之后**开始 |
| `ABORT` | 立即停止，下次 poll 从**这条**重新开始（等于"这条没消费"） |
| `COMMIT` | 提交到目前为止的所有消费，然后继续（一般拼装大消息时用） |

举例："下游队列满就先停下"：

```java
ControlledFragmentHandler h = (buffer, offset, length, header) -> {
    if (downstream.isFull()) return Action.ABORT;    // 不消费
    downstream.enqueue(buffer, offset, length);
    return Action.CONTINUE;
};
subscription.controlledPoll(h, 10);
```

### 6.2.3 BlockHandler

给"我不关心 fragment 边界，只想拿到连续字节"的场景（比如 Archive 的 recording）：

```java
BlockHandler bh = (buffer, offset, length, sessionId, termId) -> {
    // length 是本次连续读到的字节数，可能包含多条 fragment
    file.write(buffer, offset, length);
};
subscription.blockPoll(bh, 4096);
```

## 6.3 fragmentLimit 的含义

`poll(handler, limit)` 里的 limit 是"本次调用最多回调多少个 fragment"，用来控制单次 poll 的耗时上限（避免一直卡在 poll 里）。生产上常见值：

- 交易/延迟敏感：`10 ~ 50`
- 吞吐优先：`100 ~ 500`

## 6.4 多路 Subscription 的 poll 循环

真实系统一般有多路数据，一个线程 poll 多个 Subscription 是常见做法：

```java
IdleStrategy idle = new BackoffIdleStrategy(...);
while (running) {
    int total = 0;
    total += subMarket.poll(marketHandler, 20);
    total += subOrder.poll(orderHandler,   20);
    total += subFill.poll(fillHandler,    20);
    idle.idle(total);
}
```

如果你有很多 Subscription，Aeron 提供 `Aeron.addSubscription(...)` 之外的 helper：`SubscriptionUtil.pollAll(...)`（自己实现就行）。

## 6.5 广播语义再确认

一次消息经过 `pub.offer()`：

```
             ┌─▶ Subscription 1 (poll)  ← 收到
Publication ─┼─▶ Subscription 2 (poll)  ← 收到
             └─▶ Subscription 3 (poll)  ← 收到
```

**所有** 订阅同一 (channel, streamId) 的 Subscription 都会收到 **全量**。
如果要分片给一群 worker，业务层要自己做（sessionId % workerCount，或者干脆用不同 stream）。

## 6.6 Subscription 与 Image 事件

有时候你想知道"某个 Publisher 断开了"或者"新来了一个 Publisher"，可以通过 availability handler：

```java
Aeron.Context aeronCtx = new Aeron.Context()
    .aeronDirectoryName(driver.aeronDirectoryName())
    .availableImageHandler(image -> log.info("新 Image 上线, sessionId={}", image.sessionId()))
    .unavailableImageHandler(image -> log.info("Image 下线, sessionId={}", image.sessionId()));

Aeron aeron = Aeron.connect(aeronCtx);
```

或者按 Subscription 单独设：

```java
aeron.addSubscription(
    channel, streamId,
    availableImageHandler, unavailableImageHandler);
```

## 6.7 一段"限速消费"的示例

想让某个下游只吃 100 msg/s：

```java
long rate = 100;
long start = System.nanoTime();
long consumed = 0;

ControlledFragmentHandler h = (buffer, offset, length, header) -> {
    long expected = (System.nanoTime() - start) * rate / 1_000_000_000L;
    if (consumed >= expected) return Action.ABORT;  // 太快了，先歇
    consumed++;
    // 真正业务逻辑
    return Action.CONTINUE;
};

while (running) {
    subscription.controlledPoll(h, 10);
    LockSupport.parkNanos(100_000);
}
```

## 6.8 close 与资源

- `Subscription.close()` 会释放所有 Image 与 term buffer 引用；
- `Aeron` 是所有 Sub/Pub 的父容器，`aeron.close()` 会依次关闭下辖对象；
- 建议 try-with-resources，避免文件 handle 泄漏。

## 6.9 小结

- Subscription 是聚合，Image 是"每个会话的镜像"；
- `poll` + `fragmentLimit` 是消费主循环的核心节奏；
- `controlledPoll` + `Action` 让你在业务层做精确回压。
