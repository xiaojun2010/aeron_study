# 第 08 章 · offer 返回值与 Back Pressure

## 学习目标

- 记住 Publication 的 5 种负值返回值的语义；
- 会写"分类型处理"的健壮 offer 循环；
- 深入理解 Back Pressure 在 Aeron 中的物理机制；
- 明白 Aeron 的"可靠传输"到底可靠到哪一层。

## 8.1 offer 的完整返回值

```java
public class Publication {
    public static final long NOT_CONNECTED          = -1;
    public static final long BACK_PRESSURED         = -2;
    public static final long ADMIN_ACTION           = -3;
    public static final long CLOSED                 = -4;
    public static final long MAX_POSITION_EXCEEDED  = -5;
}
```

| 值 | 何时发生 | 建议处理 |
|---|---|---|
| `> 0` | 成功，值 = 新的 stream position | ✔ |
| `NOT_CONNECTED` (-1) | 还没有 Subscriber，or 全部掉线 | 等一会儿；业务上决定是否降级 |
| `BACK_PRESSURED` (-2) | 消费者跟不上，term buffer 无空闲 | 业务上决定：等 / 丢 / 合并 / 降采样 |
| `ADMIN_ACTION` (-3) | Driver 内部管理动作（term rotate）| 立即重试 |
| `CLOSED` (-4) | Publication 已关闭 | 停止发送 |
| `MAX_POSITION_EXCEEDED` (-5) | 流位置耗尽（几乎不可能，`2^63 / term_length` 个 term 之后）| 重建 Publication |

## 8.2 反例：新手的写法

```java
publication.offer(buffer, 0, length);   // ← 完全丢弃返回值
```

上层看不到 BackPressure 或 NotConnected，消息就这么"消失"了。这是 Aeron 代码 review 里的第一条大雷。

## 8.3 正确的分类型处理

```java
long r;
while (true) {
    r = pub.offer(buffer, 0, len);
    if (r >= 0) break;                                   // 成功
    if (r == Publication.NOT_CONNECTED) {
        // 完全没有订阅者：等一下
        if (++notConnectedTries > MAX_NC_TRIES) {
            // 上层告警：下游全丢了
            metrics.mark("pub.no_subscriber");
            break;
        }
        Thread.sleep(1);
    } else if (r == Publication.BACK_PRESSURED) {
        // 下游跟不上：业务决定
        metrics.mark("pub.back_pressured");
        backpressurePolicy.onBackPressure(msg);
        Thread.yield();
    } else if (r == Publication.ADMIN_ACTION) {
        // 内部维护，立即重试
    } else if (r == Publication.CLOSED) {
        log.error("Publication 已关闭");
        return;
    } else if (r == Publication.MAX_POSITION_EXCEEDED) {
        log.error("流位置耗尽，需要新建 Publication");
        rotatePublication();
        break;
    }
}
```

## 8.4 Back Pressure 的物理机制

term buffer 上有两个 position：

- `publisherLimit` = 允许 Publisher 写到的最远位置
- 各个 Subscriber 的 `consumerPosition`

driver 会不断计算：

```
publisherLimit = min(consumerPositions) + termWindowLength
```

`termWindowLength` 通常是 `term-length / 2`。当 Publisher 试图 `offer` 超过 limit 时，`offer()` 返回 `BACK_PRESSURED`。

图示：

```
term 0 (T=16MB, window=8MB)
├───────────────────────────────────────┤
0    slow_consumer=2M         pubLimit=10M   T
                                  ↑
                          Publisher 到这就阻塞了
```

## 8.5 IPC vs UDP 的 Back Pressure 差异

- **IPC**：消费者慢 = Publisher 慢，物理上完全同步
- **UDP unicast**：Publisher 端等对方 SM (Status Message) 更新 consumer position，如果对方掉线，退化成 `NOT_CONNECTED`
- **UDP multicast**：所有订阅者共享一个 flow control 目标（Ch14 详解 min/max flow control）

## 8.6 业务侧 Back Pressure 策略

Aeron 只告诉你"消费者跟不上"，怎么办要你自己决定：

| 策略 | 场景 |
|---|---|
| **等待**（yield/sleep）| 关键订单流不能丢；下游偶尔慢 |
| **丢弃**（drop）| 行情、探针，最新一条最有价值 |
| **合并**（coalesce）| 深度快照，可以合并成一条 |
| **降采样**（downsample）| Tick 数据太密，可以每 N 条采一条 |
| **溢出到磁盘**（overflow to Archive）| 需要事后回放 |
| **扩容**（更多 subscriber worker）| 长期跟不上，加机器 |

## 8.7 tryClaim 的 Back Pressure

`tryClaim` 与 `offer` 完全相同的负值语义：

```java
long r = pub.tryClaim(msgLen, claim);
if (r < 0) { /* 相同处理 */ return; }
// 写 claim...
claim.commit();
```

区别只在于成功路径：拿到 buffer 直接写，不用再 offer。

## 8.8 Aeron 的"可靠"到底可靠到哪一层

Aeron 提供的保障：

1. **有序**：同一个 stream 上，消息顺序不会颠倒；
2. **不丢**（对于 reliable=true UDP + IPC）：driver 层负责重传；
3. **不重复**：driver 层去重。

Aeron **不** 提供的保障：

1. 消费者应用层"处理成功"后的 ack 语义（要 exactly-once 得业务自己做）；
2. Publisher 崩溃恢复后的续传（用 Archive）；
3. Subscriber 崩溃后从上次 position 恢复（要业务保存 position）。

## 8.9 常见问题

**Q: 为什么我的 offer 一直 BACK_PRESSURED？**

排查顺序：
1. 消费者线程是不是 sleep 太多、handler 太慢？
2. term-length 是不是太小？
3. IPC 模式是否所有 Subscriber 都在正常 poll？
4. UDP 是否 SM 丢包（`AeronStat` 看丢包计数）？

**Q: NOT_CONNECTED 何时消失？**

- IPC：至少一个 Subscription 在同 (channel, streamId) 上 add 成功
- UDP：Subscriber 发了第一个 SM

**Q: 高频交易场景 offer 出错允许 sleep 吗？**

不建议 sleep（哪怕 1ms 都太长），一般 spin + `Thread.onSpinWait()`。见 Ch09。

## 8.10 小结

- offer 的负值一个都不能漏
- Back Pressure 是 Aeron 的核心保护机制，不是异常
- 业务层必须给出"消费者跟不上怎么办"的答案
