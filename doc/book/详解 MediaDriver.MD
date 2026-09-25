可以把 `MediaDriver` 理解成 **Aeron 真正负责“搬运数据”的运行时引擎**。`Publication` / `Subscription` 是应用看到的 API，而真正处理共享内存、UDP 收发、流控、重传、状态维护等工作的，是 MediaDriver。

先建立总图：

```text
你的 Java Application
│
├── Publication.offer()
│
├── Subscription.poll()
│
└── Aeron Client
        │
        │ 共享内存 / command & status
        ↓
┌─────────────────────────────────────┐
│             MediaDriver             │
│                                     │
│  Conductor                          │
│  Sender                             │
│  Receiver                           │
│  Flow Control                       │
│  Retransmission                     │
│  Counters                           │
│  Log Buffer 管理                    │
└─────────────────────────────────────┘
        │
        ├──── IPC / Shared Memory
        │
        └──── UDP / Network
```

这里先纠正一个很常见的理解：

> **MediaDriver 不是 Broker。**

它不像 Kafka Broker 那样负责长期保存业务消息、管理 Topic、Consumer Group。它更像一个专门为低延迟消息运输设计的**数据平面引擎**。

---

# 1. 为什么 Aeron 需要 MediaDriver？

假设没有 MediaDriver。

你的程序自己处理：

```text
Publication
    ↓
Socket
    ↓
UDP
    ↓
Packet
    ↓
重传
    ↓
流控
    ↓
Receiver
    ↓
Buffer
    ↓
Subscription
```

那意味着每个业务程序都要处理大量底层通信问题。

Aeron 把这些工作拆出去：

```text
Application
    │
    │ 我要发送这段数据
    ↓
Aeron Client
    │
    ↓
MediaDriver
    │
    │ 怎么发送我负责
    ↓
Network / IPC
```

这样业务线程重点处理：

```text
OrderBook
Strategy
Risk
Matching
```

MediaDriver 专注：

```text
Transport
Flow Control
Network IO
Retransmission
Buffer Management
```

这其实是一个很重要的架构思想：

$$
\boxed{Application\ Logic \neq Transport\ Logic}
$$

---

# 2. MediaDriver 和 Aeron Client 是两个东西

第一次学 Aeron 最容易混淆这里。

你写：

```java
Aeron aeron = Aeron.connect();
```

创建的是：

**Aeron Client**

不是 MediaDriver。

然后：

```java
Publication publication =
    aeron.addPublication("aeron:ipc", 1001);
```

也是 Client API。

真正的关系是：

```text
Application
    │
    ↓
Aeron Client
    │
    ↓
共享内存
    │
    ↓
MediaDriver
```

甚至它们可以处于不同 JVM：

```text
JVM 1
────────────────────
MediaDriver


JVM 2
────────────────────
MarketData Collector
Aeron Client


JVM 3
────────────────────
Strategy
Aeron Client
```

三者通过 Aeron Directory 下的共享内存文件进行协调。

---

# 3. MediaDriver 可以 Embedded，也可以 Standalone

这是你实战时马上会碰到的。

### Embedded

在你的 JVM 中启动：

```java
MediaDriver driver =
    MediaDriver.launchEmbedded();

Aeron aeron =
    Aeron.connect(
        new Aeron.Context()
            .aeronDirectoryName(
                driver.aeronDirectoryName()
            )
    );
```

结构：

```text
JVM
┌───────────────────────────┐
│                           │
│ Application               │
│                           │
│ Aeron Client              │
│                           │
│ MediaDriver               │
│                           │
└───────────────────────────┘
```

优点：

```text
简单
开发方便
测试方便
生命周期容易管理
```

非常适合你现在学习。

---

### Standalone

生产环境可以单独启动 MediaDriver：

```text
Process 1
┌───────────────────┐
│ MediaDriver       │
└───────────────────┘

Process 2
┌───────────────────┐
│ Market Collector  │
│ Aeron Client      │
└───────────────────┘

Process 3
┌───────────────────┐
│ Strategy          │
│ Aeron Client      │
└───────────────────┘

Process 4
┌───────────────────┐
│ Risk              │
│ Aeron Client      │
└───────────────────┘
```

这些进程连接到同一个 MediaDriver。

这样：

```text
Collector
     ↓
Publication
     ↓
MediaDriver
     ↓
Subscription
     ↓
Strategy
```

就形成进程间 IPC。

---

# 4. MediaDriver 内部最重要的三个 Agent

这是 MediaDriver 真正值得理解的地方。

先记：

```text
MediaDriver
│
├── DriverConductor
│
├── Sender
│
└── Receiver
```

这三个东西是核心。

⚠️ **一个易混点**：Client 端其实也有一个 `ClientConductor` 线程（跑在 Aeron client 里），
它和 driver 端的 `DriverConductor` 通过共享内存里的 command ring buffer 通信：

```text
Application Thread
     │
     │ aeron.addPublication(...)
     ↓
ClientConductor  ─── to-driver ring ───▶  DriverConductor
        ▲                                        │
        │                                        │ 创建 publication.log
        └─── to-clients broadcast ◀─────────────┘ 通知 client
```

后文若不特别指明"client 侧"，`Conductor` 指的都是 `DriverConductor`。

---

# 5. DriverConductor：控制中心

你可以把 `DriverConductor` 理解成：

> MediaDriver 的控制平面。

它主要处理各种生命周期和管理工作。

例如 Application 调用：

```java
aeron.addPublication(channel, streamId);
```

并不是简单地：

```text
new Publication()
```

背后大概经历：

```text
Application

addPublication()
      ↓
Aeron Client
      ↓
发送 command
      ↓
DriverConductor
      ↓
创建/管理 Publication 相关资源
      ↓
更新状态
      ↓
通知 Client
```

类似操作还有：

```text
addPublication
removePublication

addSubscription
removeSubscription

client connect
client timeout

image lifecycle

network publication lifecycle

counter lifecycle
```

所以可以粗略理解：

```text
DriverConductor
      ↓
负责“谁存在、谁连接谁、资源生命周期如何”
```

它不是主要搬业务 payload 的线程。

---

# 6. Sender：负责向网络发送

对于 UDP：

```text
Publication
     ↓
Log Buffer
     ↓
Sender
     ↓
UDP Socket
     ↓
Network
```

Sender 会关注：

```text
哪些 Publication 有数据？

哪些数据现在允许发送？

流控窗口到哪里？

是否需要重传？

当前网络发送位置是多少？
```

所以：

```text
Sender ≈ Network TX Engine
```

例如：

```text
Machine A

Application
     ↓
Publication.offer()
     ↓
Log Buffer
     ↓
Sender
     ↓
UDP
══════════════════════>
```

注意一个关键点：

> `Publication.offer()` 通常不是“调用 Sender.send()”。

这是理解 Aeron 性能模型非常关键的一点。

---

# 7. offer() 为什么可以这么快？

传统思维容易认为：

```java
publication.offer(buffer);
```

内部：

```text
offer()
 ↓
MediaDriver
 ↓
Sender
 ↓
Socket.send()
```

不是这么简单。

更接近：

```text
Application Thread

Publication.offer()
      ↓
写入 Log Buffer
      ↓
更新 position
      ↓
返回


                 MediaDriver Sender
                        ↓
                 发现新的数据
                        ↓
                    UDP Send
```

也就是说：

```text
Application Thread
       │
       │ 写共享内存
       ↓
   Log Buffer
       ↑
       │读取
       │
Sender Thread
```

这使：

```text
业务线程
```

和：

```text
Network IO Thread
```

解耦。

这也是 Aeron 高性能的核心设计之一。

---

# 8. Receiver：负责接收网络数据

另一边：

```text
Network
   ↓
UDP
   ↓
Receiver
   ↓
Log Buffer / Image
   ↓
Subscription.poll()
   ↓
Application
```

所以：

```text
Sender   → TX

Receiver → RX
```

非常好记。

例如两台机器：

```text
Machine A                       Machine B

Application                     Application
    │                               ↑
Publication                    Subscription
    │                               ↑
LogBuffer                       Image/Buffer
    ↑                               ↑
Sender ─────── UDP ───────→ Receiver
```

---

# 9. 那 IPC 为什么这么快？

现在有意思了。

如果：

```java
channel = "aeron:ipc";
```

发送者和接收者在同一台机器。

不需要：

```text
UDP
 ↓
Kernel network stack
 ↓
NIC
 ↓
Network
```

可以使用共享内存机制。

简化理解：

```text
Publisher Process

Publication.offer()
       ↓
   Log Buffer
       ↓
   Shared Memory
       ↑
Subscription.poll()

Subscriber Process
```

所以 IPC 场景里：

```text
Publisher
     ↓
Shared Memory
     ↓
Subscriber
```

MediaDriver 仍然负责控制、生命周期、position/counter 等管理，但 payload 的数据路径可以非常高效。

**特别澄清**：

```text
IPC 场景下，Sender / Receiver 线程完全不参与数据流！

  ✓ Publisher 直接 mmap → 写 term buffer
  ✓ Subscriber 直接 mmap → 读 term buffer
  ✗ 数据不经 Sender
  ✗ 数据不经 Receiver
```

Sender / Receiver 只在 UDP 场景干活。IPC 场景 driver 只留 Conductor
做 handshake / lifecycle / counter；这就是 IPC 亚微秒延迟的物理基础。

---

# 10. Log Buffer 是理解 MediaDriver 的第二道门槛

现在我们把前面的模型升级。

不要再想：

```text
Publication
   ↓
MediaDriver
   ↓
Subscription
```

更准确应该开始想：

```text
Publication
     │
     │ append
     ↓
┌──────────────────────────┐
│       Log Buffer         │
│                          │
│ msg1 msg2 msg3 msg4 ...  │
└──────────────────────────┘
        ↑             ↑
        │             │
      Sender      Subscriber
```

这里的数据不是：

```text
Java Object
Java Object
Java Object
```

而更接近：

```text
Binary Frame
Binary Frame
Binary Frame
```

例如：

```text
┌─────────┬──────────────────┐
│ Header  │ Payload          │
├─────────┼──────────────────┤
│ Header  │ Payload          │
├─────────┼──────────────────┤
│ Header  │ Payload          │
└─────────┴──────────────────┘
```

这也是为什么 Aeron API 大量使用：

```java
DirectBuffer
UnsafeBuffer
AtomicBuffer
```

而不是：

```java
Object
```

---

# 11. Term Buffer

继续往下一层。

Aeron 的 Log Buffer 并不是无限增长。

核心上会围绕多个 Term Buffer 循环工作。

可以先建立这个简化模型：

```text
Log Buffer

┌────────────────────────────┐
│ Term 0                     │
│ ████████████████████████   │
└────────────────────────────┘

┌────────────────────────────┐
│ Term 1                     │
│ █████████                  │
└────────────────────────────┘

┌────────────────────────────┐
│ Term 2                     │
│                            │
└────────────────────────────┘
```

⚠️ **关键事实**：Aeron 的每个 Publication 拥有 **恰好 3 个** term buffer，
不是可变数量。它们在 publication.log 文件里连续排列，各自等长（就是 `term-length`）：

```text
publication.log 文件 = 元信息 + 3 × term-length

term 状态循环:
   Active (正在写)  ──▶  Dirty (刚写完还没被清理)  ──▶  Clean (可复用)
        ↑                                                     │
        └─────────────────────────────────────────────────────┘
```

三段轮转是"Publisher 追尾自己"的安全垫：Publisher 要复用某个 term 之前，
必须等这个 term 上的所有订阅者都 poll 过（否则触发 BackPressure）。

例如：

```text
Term 0
   ↓
Term 1
   ↓
Term 2
   ↓
Term 0 reuse
   ↓
...
```

所以 Aeron 不是：

```text
消息永远保存
```

而是：

> **有限容量的循环传输日志。**

这和 Kafka 有根本区别。

Kafka：

```text
append
append
append
append
...
     ↓
长期保留
```

Aeron Transport：

```text
Term0
 ↓
Term1
 ↓
Term2
 ↓
reuse
```

如果要长期记录：

```text
Aeron Archive
```

才登场。

---

# 12. MediaDriver 和 Back Pressure 的关系

这就接上你上一问了。

假设：

```text
Publisher Position
      =
1000

Subscriber Position
      =
700
```

允许窗口：

```text
500
```

那么：

$$
PositionLimit = min(SubscriberPosition_i) + Window
$$

（多个订阅者时取最慢那个）

$$
=700+500=1200
$$

⚠️ **精确一点**：

- `Window` 的官方名字是 `termWindowLength`，默认 = `term-length / 2`
  （term-length 16 MB → window 8 MB）；
- IPC 与 unicast UDP 用 `min`（保证最慢订阅者不掉队）；
- multicast 场景下可以通过 `fc=min|max|tagged` 选择合并策略（见本书 Ch14）。

Publisher：

```text
1000
```

还能继续：

```text
200
```

消费者不动：

```text
Subscriber
700
```

Publisher 一路到：

```text
1200
```

发送窗口耗尽。

此时：

```java
publication.offer(...)
```

就可能返回：

```java
Publication.BACK_PRESSURED
```

所以 MediaDriver 不只是：

> 帮你发 UDP。

它还维护整个 Transport 的：

```text
Position
Flow Control
Connection State
Counters
Network State
```

---

# 13. MediaDriver 还有 Counters

这是生产系统非常重要的一部分。

MediaDriver 会暴露大量 Counters。

你可以把它们理解成：

```text
MediaDriver 的 Prometheus metrics
```

当然实现机制不是 Prometheus。

例如你会关心：

```text
Publisher position

Subscriber position

Sender position

Receiver position

Flow control limit

Errors

Retransmits

NAKs

Short sends

Driver heartbeat
```

这对交易系统非常重要。

因为你不能只监控：

```text
CPU = 30%
Memory = 2GB
```

真正需要知道：

```text
Strategy 落后了吗？

Publication 被背压了吗？

Receiver 有丢包吗？

是否正在 retransmit？

Client 是否还活着？
```

这才是 Aeron observability。

---

# 14. MediaDriver 还负责 Client 存活检测

Application：

```text
Aeron Client
```

和 Driver 之间存在心跳/超时机制。

简化理解：

```text
Client
  │
  │ keepalive
  ↓
MediaDriver
```

如果 Client：

```text
Crash
Kill -9
STW 太久
```

Driver 需要知道：

> 这个 Client 已经不存在了。

否则 Publication、Subscription、Counter 等资源可能永远残留。

所以 DriverConductor 会负责：

```text
Client lifecycle
```

管理。

**默认超时值**（Aeron 1.44）：

| Context 参数 | 默认值 | 含义 |
|---|---|---|
| `clientLivenessTimeoutNs` | 10 秒 | Client 心跳失联多久判定死亡 |
| `imageLivenessTimeoutNs` | 10 秒 | Image 未收到数据多久判定失效 |
| `publicationLingerTimeoutNs` | 5 秒 | Publication 关闭后保留时间 |
| `publicationUnblockTimeoutNs` | 15 秒 | Publisher 卡住多久强制 unblock |

高频交易系统通常调短这些值，追求快速失败。

---

# 15. Threading Mode 非常重要

当你进一步配置：

```java
MediaDriver.Context ctx =
    new MediaDriver.Context();
```

会遇到：

```java
threadingMode(...)
```

常见思想包括：

```text
DEDICATED
SHARED_NETWORK
SHARED
INVOKER
```

这里先重点理解 `DEDICATED`。

它大概就是：

```text
MediaDriver

Thread 1
DriverConductor

Thread 2
Sender

Thread 3
Receiver
```

优点：

```text
隔离好
延迟稳定
减少相互影响
```

代价：

```text
更多线程
更多 CPU
```

这也是低延迟系统经常愿意做的交换：

$$
CPU \uparrow
$$

换：

$$
Latency\ Variance \downarrow
$$

尤其做市系统通常非常关心后者。

### 四种模式完整对比

```text
DEDICATED (线程数=3)
┌─────────────────────────────────────┐
│  Thread 1  DriverConductor          │
│  Thread 2  Sender                   │
│  Thread 3  Receiver                 │
└─────────────────────────────────────┘
适合: 高频交易 / 每线程绑核


SHARED_NETWORK (线程数=2)
┌─────────────────────────────────────┐
│  Thread 1  DriverConductor          │
│  Thread 2  Sender + Receiver 合体   │
└─────────────────────────────────────┘
适合: 中等负载 / 单机多 driver 场景


SHARED (线程数=1)
┌─────────────────────────────────────┐
│  Thread 1  Conductor + Sender +     │
│            Receiver 全部合成一个     │
└─────────────────────────────────────┘
适合: demo / 单元测试 / CPU 紧张


INVOKER (线程数=0)
┌─────────────────────────────────────┐
│  没有 driver 线程                    │
│  业务代码自己周期性调                 │
│  driver.sharedAgentInvoker().invoke()│
└─────────────────────────────────────┘
适合: 嵌入 Netty EventLoop / 自己控节奏
```

选择原则：

```text
学习 / 测试         → SHARED
一般业务微服务      → SHARED_NETWORK
低延迟生产          → DEDICATED + 绑核 + NoOpIdleStrategy
特殊单线程模型      → INVOKER
```

---

# 16. IdleStrategy 又出现了

三个 Agent：

```text
Conductor
Sender
Receiver
```

都需要不断工作。

例如：

```text
while (running) {

    int workCount = doWork();

    idleStrategy.idle(workCount);
}
```

所以 MediaDriver 内部本质上大量采用：

```text
Agent Loop
```

思想。

例如：

```text
Sender Thread

while (running) {

    查找需要发送的数据

    发送 UDP

    处理 retransmission

    idle()
}
```

这就是为什么 Aeron 和 Agrona 是紧密关联的。

Aeron 底层大量使用 Agrona 提供的：

```text
Agent
AgentRunner
IdleStrategy
DirectBuffer
UnsafeBuffer
Counters
RingBuffer
```

---

# 17. 为什么 MediaDriver CPU 有时候看起来很高？

以后你部署 Aeron 很可能遇到：

```text
CPU 100%

但系统似乎没多少消息。
```

别马上认为有 Bug。

如果配置：

```java
BusySpinIdleStrategy
```

线程可能：

```text
while(true) {
    poll();
}
```

没有消息也不 sleep。

CPU：

```text
100%
```

但换来的是：

```text
不 sleep
↓
不用 wakeup
↓
减少调度延迟
↓
降低 latency jitter
```

所以：

> 在低延迟系统里，“CPU 利用率高”不一定代表系统繁忙。

可能是**主动拿 CPU 换延迟确定性**。

这跟传统 Web 服务的性能观念差别很大。

---

# 18. Aeron Directory 是什么？

你启动 MediaDriver 后，经常看到：

```text
/aeron-xxx/
```

这就是：

```text
Aeron Directory
```

可以粗略理解为 Driver 与 Client 的：

```text
共享内存通信区域
+
状态区域
+
控制区域
```

里面会涉及类似：

```text
cnc.dat
```

CNC：

```text
Command & Control
```

非常重要。

简化：

```text
Application
    │
    │ command
    ↓
┌──────────────────┐
│     cnc.dat      │
└──────────────────┘
    ↑
    │
MediaDriver
```

这里面还有 counters、error log 等信息。

**cnc.dat 完整布局**（Aeron 1.44）：

```text
+─────────────────────────────────+  ← offset 0
│  MetaData Header                 │  版本、buffer 长度、启动时间
│  (Cache-line 对齐, 128 bytes)    │
+─────────────────────────────────+
│  to-driver ring buffer            │  Client → Driver 命令
│  (Client 发指令给 Driver)         │  add/removePublication 等
+─────────────────────────────────+
│  to-clients broadcast buffer      │  Driver → Client 事件
│  (Driver 广播事件给所有 Client)   │  onPublicationReady 等
+─────────────────────────────────+
│  counter metadata buffer          │  counter 名字 + 标签
+─────────────────────────────────+
│  counter values buffer            │  ★ counter 值（AeronStat 读这里）
│  (每 counter 128 字节槽位)        │
+─────────────────────────────────+
│  error log buffer                 │  异常环形 buffer
│  (ErrorLogReader 读这里)          │
+─────────────────────────────────+
```

生产运维必看：counter values 和 error log。

所以：

```text
Aeron Client
```

如何找到：

```text
MediaDriver
```

一个关键就是：

```text
双方使用同一个 aeronDirectoryName
```

⚠️ 注意 `launch()` 和 `launchEmbedded()` 的目录默认值不同：

```text
MediaDriver.launch()          → 默认: ${TMPDIR}/aeron-${user}    固定, 唯一
MediaDriver.launchEmbedded()  → 自动: ${TMPDIR}/aeron-${user}-<pid>-<random>  可并存
```

生产独立部署用 `launch()`；本机测试 / 内嵌用 `launchEmbedded()`。

---

# 19. 用你的行情系统看 MediaDriver

现在把这些概念放回你真正的架构。

```text
                    Binance / OKX / Bitget
                              │
                              ↓
                       Netty WebSocket
                              │
                              ↓
                       Market Parser
                              │
                              ↓
                    Publication.offer()
                              │
                              ↓
                  ┌────────────────────┐
                  │    Log Buffer      │
                  └────────────────────┘
                              │
              ┌───────────────┼──────────────┐
              │                              │
             IPC                            UDP
              │                              │
              ↓                              ↓
        Local Strategy                Remote Machine
              │                              │
              ↓                              ↓
      Subscription.poll()                Receiver
                                             ↓
                                         Strategy
```

MediaDriver 在这里主要负责：

```text
控制 Publication / Subscription 生命周期
管理 Buffer
管理 Position
管理 Flow Control
管理 UDP Sender
管理 UDP Receiver
处理 Retransmission
管理 Counters
监控 Client 生命周期
```

而它**不应该负责**：

```text
✗ 消息的业务语义（下单？行情？成交？——driver 不关心）
✗ 消息的序列化 / 反序列化（业务自己 encode/decode）
✗ 消息的路由 / 分派（不做"按 symbol 路由"这种事）
✗ 消息的持久化（那是 Aeron Archive 的活）
✗ 幂等 / 去重 / 事务（业务层自己保证）
✗ 权限 / 认证 / 审计（driver 不做鉴权）
✗ Topic / Consumer Group 管理（Aeron 没有这两个概念）
```

一句话总结：

> **MediaDriver 只负责把字节从一处搬到另一处，并保证顺序、可靠、流控。
> 一切"业务含义"都属于应用层。**

这条边界一旦模糊，就会写出"往 driver 里塞业务逻辑"的怪东西，性能立刻崩。

---

# 20. 校验总结：MediaDriver 学到什么算过关？

给自己打勾：

```text
□ 能画出 MediaDriver / Aeron Client / Application 三层关系
□ 能说出 DriverConductor / Sender / Receiver 各干什么
□ 知道 IPC 场景下 Sender / Receiver 不参与数据流
□ 明白 offer() 是"写 log buffer"而不是"直接 send UDP"
□ 知道每个 Publication 有 3 个 term buffer 循环
□ 记得 publisherLimit = min(consumerPos) + termWindowLength
□ 会区分 launch() 与 launchEmbedded() 目录差异
□ 4 种 ThreadingMode 各自适用场景说得出来
□ 知道 cnc.dat 里主要有 to-driver / to-clients / counters / error log
□ 明白 CPU 100% 可能是"用 CPU 换延迟稳定性"
□ 知道 clientLivenessTimeoutNs 默认 10 秒
□ 能列出 MediaDriver 不负责的 7 件事
```

全部打勾 → 可以进入 Ch05 (Publication) / Ch06 (Subscription) 深入 API。

---

# 21. 常见误区速查

| ❌ 错的认知 | ✔ 正确的认知 |
|---|---|
| MediaDriver 是 Broker | 它是数据平面引擎，不做业务分发 |
| offer() 立刻发 UDP | offer() 只写 log buffer，Sender 异步 pick |
| aeron:ipc 也要经过 Sender | IPC 完全走共享内存，Sender/Receiver 不参与 |
| Aeron 会永久保存消息 | Transport 只保 3×term 时间，长期存要 Archive |
| Stream ID 就是 Kafka Topic | Stream ID 只是 int，没有元数据/分区/权限 |
| CPU 100% 就是有 bug | 可能是 BusySpin 换延迟稳定性 |
| launchEmbedded 与 launch 目录一样 | 完全不一样，embedded 是随机目录 |
| Sub 越多 driver 越忙 | IPC 场景下不会——sub 只是各自 mmap 读 |
| offer 失败等于抛异常 | offer 返回负值，业务自己判断怎么处理 |
| 一个 term buffer 就够了 | 必须是 3 个循环，2 个不够（无缓冲），4+ 浪费 |

---

# 22. 与本书其它章节的映射

深入某个方面时可以跳转到对应章节：

```text
本文话题                  → 深入章节
─────────────────────────────────────
Threading Mode 具体调优    → Ch04 §4.3-4.4
Publication API 全解       → Ch05
Subscription / poll        → Ch06
Channel URI 参数           → Ch07
offer 返回值与 BackPressure → Ch08
IdleStrategy 具体对比      → Ch09
DirectBuffer / UnsafeBuffer → Ch10
Image / Term Buffer 内部    → Ch13
流控 / 拥塞 / NAK          → Ch14
Counters 与 AeronStat      → Ch15
UDP Multicast              → Ch16
MDC                        → Ch17
Archive 录制/回放          → Ch19-20
Cluster (基于 driver 之上)  → Ch21-22
部署 / 系统调优            → Ch23
排错手册                    → Ch24
```

---

# 23. 结语

MediaDriver 的复杂度在 Aeron 里排第二（第一是 Cluster），但对初学者的心智负担最大——
因为它是"看不见摸不着的引擎"，你甚至不需要写一行代码就能启动它。

建议：

```text
第一遍：读到 §10 (Log Buffer) 停下，跑 demo01 / demo03 感受一下
第二遍：读完全文，配合 Ch04 深入 Context 参数
第三遍：出问题时回来查 §12 (BackPressure) / §14 (超时) / §21 (误区表)
```

MediaDriver 学透了，Aeron 剩下的 API 都是"在 driver 之上的语法糖"。
