# 第 05 章 · Publication 全解

## 学习目标

- 会用 `Publication` 与 `ExclusivePublication`，能说清区别；
- 明白 `offer` / `tryClaim` / `offer with vectors` 三种发送 API；
- 掌握 Publication 的生命周期与常见状态；
- 了解 stream position、term id 等元信息。

## 5.1 两种 Publication

| API | 类型 | 线程安全 | 用途 |
|---|---|---|---|
| `aeron.addPublication(channel, streamId)` | `ConcurrentPublication` | ✔ 多线程可同时 offer | 常规业务 |
| `aeron.addExclusivePublication(channel, streamId)` | `ExclusivePublication` | ✘ 只允许单线程调用 | 极致低延迟、SPSC 场景 |

`ExclusivePublication` 的优势：
- 不需要 CAS，写入路径少一次原子操作；
- 支持 `tryClaim`（零拷贝写入）；
- 支持 `offer` 的 batch。

示例：

```java
ExclusivePublication pub = aeron.addExclusivePublication("aeron:ipc", 1001);
// pub 只允许一个线程调 offer；如果多线程都要发，就要用 addPublication
```

## 5.2 三种发送 API

### 5.2.1 offer(buffer, offset, length)

最常见，返回值语义在 Ch08 详解：

```java
long result = pub.offer(buffer, 0, length);
// result >= 0 : 发送成功，返回新的 stream position
// result < 0  : NOT_CONNECTED / BACK_PRESSURED / ADMIN_ACTION / CLOSED / MAX_POSITION_EXCEEDED
```

### 5.2.2 tryClaim(length, bufferClaim)

零拷贝写入，直接给你返回 term buffer 里的一段内存，你写完 `commit()`：

```java
BufferClaim claim = new BufferClaim(); // 复用
long result = pub.tryClaim(msgLength, claim);
if (result > 0) {
    MutableDirectBuffer dst = claim.buffer();
    int offset = claim.offset();
    dst.putLong(offset, System.nanoTime());
    // ... 写更多字段
    claim.commit();  // 提交；如果异常路径要 abort()
} else {
    // 与 offer 相同的负值语义
}
```

`tryClaim` 的场景：
- 消息大小提前已知；
- 生成消息成本高（比如复杂计算），希望"在 term buffer 上直接写"，避免 heap→direct 拷贝；
- 与 SBE encoder 配合，直接在 claim buffer 上 encode。

⚠️ 只在 `ExclusivePublication` 上用 `tryClaim` 才最高效（`ConcurrentPublication.tryClaim` 内部要 CAS）。

### 5.2.3 offer(buffer[], offset[], length[]) —— 向量发送

一次把多段拷贝拼成一条 Aeron 消息：

```java
DirectBufferVector[] vectors = new DirectBufferVector[] {
    new DirectBufferVector(headerBuf, 0, 16),
    new DirectBufferVector(bodyBuf,   0, 128)
};
long r = pub.offer(vectors);
```

省一次业务侧的合并拷贝。

## 5.3 Publication 是"连接尚未就绪"的

`addPublication()` 返回后并不立刻可用：

```java
Publication pub = aeron.addPublication(CHANNEL, STREAM_ID);
while (!pub.isConnected()) Thread.yield();   // ← 关键
```

`isConnected()` 变 true 的时机：
- IPC：等 driver 里有至少一个 Subscription 打到同一 stream 上；
- UDP：等对方 Subscription 通过 SM (status message) 表明自己在监听。

## 5.4 stream position 与 term

Aeron 内部把流按 term 切段（term buffer 是"某个 term 的存储"）：

```
term_id=0, offset=0..(T-1)    term_id=1, offset=0..(T-1)    ...
├──────────── term 0 ─────────┤├────────── term 1 ────────┤

stream position = term_id * T + term_offset
```

Publication 上可读：

```java
long pos       = pub.position();
int  termId    = pub.termId();
int  termOffset = pub.termOffset();
int  initialTermId = pub.initialTermId();
int  termBufferLength = pub.termBufferLength();
```

用途：监控 / 调试 / 打 checkpoint。

## 5.5 Publication 的生命周期

```
addPublication ──► not connected ──► connected ──► closed
        (0..N ms)                              │
                                               ▼
                                       (可能变成 not connected：所有 sub 掉线)
```

- 一个 Publication 可以经历多次"connected → not connected → connected"（订阅端来来去去）
- `Publication.close()` 是幂等的，多次关闭无害
- 使用 try-with-resources 是最简单的方式

## 5.6 多线程调用规则

| 场景 | 建议 |
|---|---|
| 单线程 producer | `ExclusivePublication` |
| 多线程 producer 且性能不敏感 | `ConcurrentPublication`（默认） |
| 多线程 producer 且需要低延迟 | 每线程一个 `ExclusivePublication`（不同 sessionId），下游多路合并 |

## 5.7 一段可运行示例

放到 `demo05_extra/ExclusivePubDemo.java` 就能跑：

```java
try (MediaDriver driver = MediaDriver.launchEmbedded();
     Aeron aeron = Aeron.connect(new Aeron.Context()
             .aeronDirectoryName(driver.aeronDirectoryName()));
     ExclusivePublication pub = aeron.addExclusivePublication("aeron:ipc", 42);
     Subscription sub = aeron.addSubscription("aeron:ipc", 42)) {

    while (!pub.isConnected() || !sub.isConnected()) Thread.yield();

    BufferClaim claim = new BufferClaim();
    for (int i = 0; i < 5; i++) {
        long r;
        while ((r = pub.tryClaim(16, claim)) < 0) Thread.yield();
        MutableDirectBuffer buf = claim.buffer();
        int off = claim.offset();
        buf.putLong(off, System.nanoTime());
        buf.putInt(off + 8, i);
        claim.commit();
        log.info("tryClaim 发送 i={} pos={}", i, r);
    }

    long deadline = System.currentTimeMillis() + 500;
    while (System.currentTimeMillis() < deadline) {
        sub.poll((b, o, l, h) -> {
            long ts = b.getLong(o);
            int  i  = b.getInt(o + 8);
            log.info("收到 i={} ts={}", i, ts);
        }, 10);
    }
}
```

## 5.8 小结

- Publication 是 Aeron 的发送句柄，先 `isConnected()` 再 offer
- `ExclusivePublication` + `tryClaim` 是零拷贝写入的黄金组合
- 生产系统对每一个 `long r = offer(...)` 都要处理返回值（Ch08）
