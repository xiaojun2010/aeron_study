# 第 16 章 · UDP Multicast

## 学习目标

- 理解 UDP 组播的核心地址范围与 TTL；
- 会写 Aeron 组播 URI；
- 知道 IGMP、`interface` 参数的用途；
- 明白组播 flow control 的独特性。

## 16.1 何时使用组播

一发多收、订阅方数量不确定或经常变动 → 组播。典型场景：
- 行情分发（1 个 gateway → N 个策略/风控/录制）
- 服务发现广播
- 集群心跳

不用组播的场景：
- 云环境（AWS/GCP 默认不支持）
- 订阅方 <= 3 且稳定 → 直接多个单播更简单
- 需要跨路由器 → 需运维配 IGMP snooping / PIM

## 16.2 IP 组播地址范围

| 范围 | 用途 |
|---|---|
| 224.0.0.0 ~ 224.0.0.255 | 本地网段（TTL=1） |
| 224.0.1.0 ~ 238.255.255.255 | 全网组播 |
| **239.0.0.0 ~ 239.255.255.255** | 管理域组播（推荐生产用） |

**建议自研系统选 239.x.y.z 段**，避免与知名协议冲突。

## 16.3 Aeron 组播 URI

```
aeron:udp?endpoint=239.1.2.3:40456|interface=eth0
```

- `endpoint`：组播 IP + 端口
- `interface`：本机使用的网卡地址（多网卡机器必须显式指定）
- `ttl`：TTL，默认 0（本机），要跨路由器改成 8+

同一 (group, port) 上所有订阅方都会收到相同数据。

## 16.4 一个可运行示例

Publisher：

```java
String channel = "aeron:udp?endpoint=239.1.2.3:40456|ttl=1";
Publication pub = aeron.addPublication(channel, 1001);
```

Subscriber（可以起 N 个进程，全部收到）：

```java
String channel = "aeron:udp?endpoint=239.1.2.3:40456";
Subscription sub = aeron.addSubscription(channel, 1001);
```

Linux 上先确保系统允许组播：

```bash
sudo sysctl -w net.ipv4.conf.all.mc_forwarding=1
sudo ip route add 239.0.0.0/8 dev eth0
```

macOS 通常本机测试直接可用。

## 16.5 interface 参数细节

多网卡机器（比如生产服务器 mgmt + trade 两块网卡）：

```
aeron:udp?endpoint=239.1.2.3:40456|interface=10.0.0.10
aeron:udp?endpoint=239.1.2.3:40456|interface=10.0.0.10/24     # 用 CIDR 匹配
```

`interface` 的作用：
- Publisher：出口选哪张网卡；
- Subscriber：加入组播组时用哪张网卡（否则可能加错）。

## 16.6 组播 flow control（复习 Ch14）

组播的独特问题：Publisher 只有一份，但 Subscriber 一群，谁慢谁快都可能。

| 策略 | 语义 |
|---|---|
| `fc=min` | Publisher 跟着最慢订阅方 |
| `fc=max` | Publisher 跟着最快，慢订阅方掉队 |
| `fc=tagged,g:0/1` | 只看 gtag=1 的订阅方 |

生产上 **行情分发** 常用 `fc=max` + 大 term-length（丢的订阅方通过 Archive replay 补回来）。

## 16.7 IGMP snooping

如果不设 IGMP snooping，交换机会把组播包广播给整个 VLAN，噪音很大。运维需要：

```
switch(config)# ip igmp snooping vlan 100
```

Java 侧不用改代码，只是交换机层面的配置。

## 16.8 连接性诊断

```bash
# 1. 网卡是否加入组
netstat -g

# 2. 是否收到组播
tcpdump -i eth0 -n dst 239.1.2.3

# 3. TTL 是否够
ttl 一定要 >= 跨的路由器数量
```

## 16.9 常见陷阱

1. **组播地址端口撞车**：不同业务用了相同 (group, port)，串包。
2. **多网卡默认路由错**：Publisher 发到 mgmt 网卡了，Subscriber 在 trade 网卡看不到。
3. **TTL=0 想跨机**：0 只在本机，1 只在本网段，要加。
4. **云上跑组播**：多数公有云不支持，除非用专线或者 overlay。
5. **fc=max 但没有配 Archive 补丢**：慢订阅方永远丢数据。

## 16.10 小结

- 组播 URI 三要素：`endpoint=239.x|interface=xxx|ttl=N`
- 组播 flow control 一定要主动选（min / max / tagged）
- 组播不是通用方案，云上首选 MDC（Ch17）
