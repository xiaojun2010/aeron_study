# 第 07 章 · Channel URI 完全指南

## 学习目标

- 认全 `aeron:ipc` / `aeron:udp` 的参数；
- 学会写 unicast / multicast / MDC 的 URI；
- 掌握 `ChannelUriStringBuilder` 优雅构造；
- 知道每个参数背后的物理意义（sessionId, MTU, term-length, initial-term-id…）。

## 7.1 URI 基本形式

```
aeron:ipc[?key1=value1|key2=value2|...]
aeron:udp[?key1=value1|key2=value2|...]
```

分隔符是 **`|`**（不是 `&`），初学者最容易写错。

## 7.2 常见 IPC 组合

```java
"aeron:ipc"                                    // 最简单
"aeron:ipc?term-length=1048576"                // 指定 term buffer 1 MB
"aeron:ipc?alias=market-data"                  // 起个别名，方便 CncFileReader 观察
```

## 7.3 常见 UDP 组合

```
aeron:udp?endpoint=<host:port>                                       # 单播
aeron:udp?endpoint=224.0.0.1:40456|interface=eth0                    # 组播 + 指定网卡
aeron:udp?control=host:port|control-mode=dynamic                     # MDC，Ch17
aeron:udp?endpoint=host:port|mtu=8192|term-length=16777216           # 大包 + 大 term
aeron:udp?endpoint=host:port|ttl=8                                   # 组播 TTL
```

## 7.4 参数一览

| 参数 | 值 | 意义 |
|---|---|---|
| `endpoint` | `host:port` | 收方绑定地址 |
| `interface` | `host[:port]` | 发方使用的网卡地址 |
| `control` | `host:port` | MDC 的控制端点 |
| `control-mode` | `manual` / `dynamic` | MDC 模式 |
| `mtu` | 整数 | MTU 字节，超过则拆片。UDP 默认 1408 |
| `term-length` | 2 的幂，最大 1 GB | 单个 term buffer 大小 |
| `init-term-id` / `term-id` / `term-offset` | 整数 | 手工指定起始位置（Archive replay 常用） |
| `session-id` | 32-bit | 手动指定 sessionId |
| `linger` | 纳秒 | Publication 关闭后保留时间，方便迟到订阅者 |
| `reliable` | true/false | 是否启用重传（默认 true） |
| `tags` | 逗号分隔 | 给 driver 打 tag，可通过 tag 引用 |
| `alias` | 字符串 | 别名（cnc 中显示） |
| `sparse` | true/false | term 文件是否稀疏 |
| `so-sndbuf` / `so-rcvbuf` | 字节数 | socket send/receive buffer |
| `ttl` | 1~255 | 多播 TTL |

## 7.5 优雅构造：ChannelUriStringBuilder

字符串拼接容易出错，官方给了 builder：

```java
String channel = new ChannelUriStringBuilder()
    .media("udp")
    .endpoint("192.168.1.20:40456")
    .mtu(8192)
    .termLength(16 * 1024 * 1024)
    .reliable(true)
    .alias("market-data")
    .build();
// aeron:udp?endpoint=192.168.1.20:40456|mtu=8192|term-length=16777216|reliable=true|alias=market-data
```

## 7.6 IPC vs UDP 参数差异

| 参数 | IPC | UDP |
|---|---|---|
| `endpoint` | ✘ | ✔ 必须 |
| `mtu` | 与 ipc-mtu-length 全局配置有关，一般不用 | 关键 |
| `term-length` | 用 `ipcPublicationTermBufferLength` | 用 `publicationTermBufferLength` |
| `control-mode` | ✘ | ✔（MDC） |

## 7.7 Session ID 与 URI

不指定 `session-id` 时，driver 会随机分配。手动指定的场景：

- **Aeron Archive replay**：让 replay 出来的流带上原始 sessionId；
- **Deterministic testing**：让测试可重现；
- **Cluster consensus module**：cluster 内部固定 sessionId 用于内部通信。

```java
String pubChannel = "aeron:udp?endpoint=host:port|session-id=12345";
// 对应 Subscription 侧可以 filter 只收这个 sessionId
```

## 7.8 URI 一致性要求

Publisher 和 Subscriber 的 URI 允许"发方多参数，收方少参数"，但 **endpoint / term-length / mtu 必须匹配**。常见组合：

- Publisher: `aeron:udp?endpoint=host:port|mtu=8192|term-length=16M`
- Subscriber: `aeron:udp?endpoint=host:port|term-length=16M`

## 7.9 跨机部署的实际写法

场景：Machine A 发 → Machine B 收

Machine B（订阅方）：

```java
String sub = "aeron:udp?endpoint=0.0.0.0:40456|term-length=16m";  // 监听全部网卡
Subscription s = aeron.addSubscription(sub, 1001);
```

Machine A（发布方）：

```java
String pub = "aeron:udp?endpoint=192.168.1.20:40456|term-length=16m";
Publication p = aeron.addPublication(pub, 1001);
```

注意：
- 收方常用 `0.0.0.0` 表示监听所有网卡；
- term-length 建议双方一致，方便对账；
- 防火墙要放开 UDP 40456。

## 7.10 Alias 与 Cnc

给 URI 加上 `alias=...`，你在 `AeronStat` / `CncFileReader` 里就能看见友好的名字，排查方便：

```bash
java -cp aeron-samples.jar io.aeron.samples.AeronStat
# 列出所有 counter，包含 alias
```

## 7.11 小结

- URI 是 Aeron 唯一"通道地址"表达方式；
- 复杂场景用 `ChannelUriStringBuilder`；
- Publisher / Subscriber URI 不需要完全相同，但 endpoint/term-length/mtu 要一致；
- Ch16 讲 multicast，Ch17 讲 MDC，会用到 `control` / `control-mode` 参数。
