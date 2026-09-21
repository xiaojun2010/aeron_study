# 第 11 章 · 大消息与 FragmentAssembler

## 学习目标

- 理解 MTU、term-length、消息大小三者的关系；
- 会用 FragmentAssembler / ControlledFragmentAssembler 拼装大消息；
- 明白 `BEGIN` / `END` fragment flag 的机制；
- 知道大消息在低延迟系统里的成本，学会规避。

## 11.1 拆包的两条边界

Aeron 会在两种情况把消息拆开：

1. **超过 MTU**：单个 UDP 包大小限制，默认 1408 字节；
2. **term buffer 满**：term 剩余空间不足以放当前消息。

对第一种，driver 会把消息切成多个"fragment"，每个 fragment 都是独立的 Aeron message frame。
对第二种，Aeron 会 padding 剩余空间 + 到下一个 term（这不算拆包，业务不感知）。

## 11.2 fragment 的 flag

Aeron header 里两个 bit 表示这个 fragment 在消息中的位置：

| flag | 含义 |
|---|---|
| `BEGIN_FRAG_FLAG = 0x80` | 消息的第一片 |
| `END_FRAG_FLAG = 0x40` | 消息的最后一片 |
| `UNFRAGMENTED = 0xC0` | 完整消息（BEGIN + END 同时置位） |

在 `FragmentHandler` 里你可以自己判：

```java
FragmentHandler h = (buf, offset, length, header) -> {
    byte flags = header.flags();
    boolean begin = (flags & 0x80) != 0;
    boolean end   = (flags & 0x40) != 0;
    // ...
};
```

但一般不这么手写，用 FragmentAssembler。

## 11.3 FragmentAssembler

```java
FragmentHandler business = (buf, offset, length, header) -> {
    // 这里的 length 是"完整消息"长度
};

// 用 assembler 包一层
FragmentHandler assembler = new FragmentAssembler(business);
subscription.poll(assembler, 10);
```

内部行为：
- 看到 `BEGIN` 但没 `END`：把 fragment 拷到 session-specific 的重组 buffer；
- 看到中间片：继续追加；
- 看到 `END`：一次性回调 business；
- 每个 sessionId 各有一份重组 buffer，互不干扰；
- 默认初始容量 4 KB，超过会 realloc（在慢路径）。

## 11.4 ControlledFragmentAssembler

对应 `controlledPoll`：

```java
ControlledFragmentHandler business = (buf, offset, length, header) -> {
    if (backlog.isFull()) return Action.ABORT;
    backlog.enqueue(buf, offset, length);
    return Action.CONTINUE;
};

ControlledFragmentHandler wrapped = new ControlledFragmentAssembler(business);
subscription.controlledPoll(wrapped, 10);
```

Action 语义与 Ch06 一致。

## 11.5 大消息在低延迟系统的成本

发送 8 KB 消息 vs 8 × 1 KB 消息，成本分布不同：

| 阶段 | 8 KB 单条 | 8 × 1 KB |
|---|---|---|
| Publisher 侧序列化 | 1 次 | 8 次 |
| Aeron 拆片 | 6 次 (MTU=1408) | 0 次 |
| UDP send | 6 次 | 8 次 |
| Subscriber 侧重组 | 6 次 + 一次 memcpy | 0 次 |
| Handler 回调 | 1 次 | 8 次 |

结论：
- 小消息 (<MTU)：单条更快；
- 大消息：可以考虑 tryClaim + SBE，尽量减少 assemble 拷贝；
- 极端场景：把大消息拆成业务分片，让 handler 增量处理，避免拼装。

## 11.6 增大 MTU

如果你的网络支持 jumbo frame（9000 字节），可以：

```
aeron:udp?endpoint=host:port|mtu=8192
```

driver 侧全局默认可以在 Context 设：

```java
ctx.mtuLength(8192);
```

⚠️ **两端 MTU 必须一致**，否则重传风暴。且需要网卡、交换机、防火墙都支持。

## 11.7 一段可运行示例

见 `aeron/src/main/java/org/example/demo07/Demo07FragmentAssembler.java`。核心结构：

```java
FragmentHandler biz = (buf, offset, length, header) -> {
    log.info("[Assembled] len={}", length);   // 每条完整消息一次
};
FragmentHandler asm = new FragmentAssembler(biz);

while (running) {
    subscription.poll(asm, 20);
}
```

发送端故意发大小 100 ~ 4096 字节的消息，log 里能看到 raw fragment 数 > 消息数。

## 11.8 常见陷阱

1. **忘记用 assembler**：只要一条消息超过 MTU 就会漏字节。业务上"发一直没超过 MTU"的假设是脆弱的——加上就好。
2. **assembler 与 handler 状态混用**：Fragment 拼装时 handler 只在 END 才被调用；不要在中间 fragment 假设"我已经拿到完整消息"。
3. **不同订阅 handler 共用一个 FragmentAssembler**：assembler 内部按 sessionId 分表，同一个实例可以，但两个不同 Subscription 不能共用同一 assembler 实例。
4. **忘记 assembler 初始大小**：默认 4 KB，如果你的消息普遍 10 KB，可以给 ctor 传 initialBufferLength 避免早期扩容。

## 11.9 小结

- MTU > term rotation ≠ 消息拆分
- FragmentAssembler 把拆包变透明
- 大消息代价高，能拆业务就拆业务
