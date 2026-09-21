# 第 10 章 · Agrona DirectBuffer 与零 GC 编程

## 学习目标

- 分清 `DirectBuffer` / `MutableDirectBuffer` / `AtomicBuffer` / `UnsafeBuffer`；
- 掌握 UnsafeBuffer 的构造、字段访问、字节序控制；
- 学会用 `ExpandableArrayBuffer` 应对未知大小消息；
- 建立"零分配"思维：DirectBuffer + 复用 + 预分配。

## 10.1 Agrona 是什么

Agrona 是 Aeron 的姊妹项目，提供：
- 高性能 buffer / concurrent data structure
- IdleStrategy
- Agent / AgentRunner 框架
- Timer wheel、one-to-one/many queue
- 无 GC 的集合类（Long2ObjectHashMap 等）

Aeron 的 `aeron-client` 会自动依赖 Agrona，你无须单独引入。

## 10.2 Buffer 家族

```
DirectBuffer               只读接口
     ↑
MutableDirectBuffer        可写接口
     ↑
AtomicBuffer               支持 volatile / CAS 语义
     ↑
UnsafeBuffer               具体实现（默认）
```

日常 99% 用 `UnsafeBuffer`。它可以包装：
- `byte[]`（堆上）
- `ByteBuffer.allocateDirect(n)`（堆外 direct）
- 直接指定 memory address（native memory）

```java
UnsafeBuffer a = new UnsafeBuffer(new byte[256]);
UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
UnsafeBuffer c = new UnsafeBuffer(mmapAddress, mmapLength);
```

Aeron 里所有 Publication 相关的 buffer 建议用 direct（避免 JVM 把它 copy 到 native）。

## 10.3 常用读写 API

```java
UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(64));

// 写
buf.putInt(0, 12345);
buf.putLong(4, System.nanoTime());
buf.putDouble(12, 3.14);
buf.putBytes(20, someBytes);

// 读
int    a = buf.getInt(0);
long   b = buf.getLong(4);
double c = buf.getDouble(12);
byte[] d = new byte[16];
buf.getBytes(20, d);
```

字节序默认是 **native**（Little-Endian on x86/ARM）。跨平台或跨语言时要显式：

```java
buf.putIntAscii(0, 12345);                              // 不带字节序
buf.putInt(0, 12345, ByteOrder.BIG_ENDIAN);             // 显式 BE
```

## 10.4 String / VarChar 处理

Aeron 的 buffer 没有原生 varchar 概念，需要自己写：

```java
// 写：长度 + UTF-8 字节
byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
buf.putInt(offset, bytes.length);
buf.putBytes(offset + 4, bytes);
int newOffset = offset + 4 + bytes.length;

// 读
int len = buf.getInt(offset);
byte[] bytes = new byte[len];
buf.getBytes(offset + 4, bytes);
String str = new String(bytes, StandardCharsets.UTF_8);
```

更高效的做法是用 SBE 的 `varData` 类型（Ch12），或者用固定长度字符（例如 symbol 最长 12 字符，直接 putBytes 12 字节）。

## 10.5 复用 buffer

低延迟原则 #1：**不要在热路径 new 对象**。

```java
public class MarketPublisher {
    private final Publication pub;
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256)); // 分配一次

    public void publish(long ts, double bid, double ask) {
        buffer.putLong(0, ts);
        buffer.putDouble(8, bid);
        buffer.putDouble(16, ask);
        long r = pub.offer(buffer, 0, 24);
        // ... 处理返回值
    }
}
```

## 10.6 ExpandableArrayBuffer / ExpandableDirectByteBuffer

如果消息大小事先未知（比如 SBE 里有 varData），Agrona 提供可自动扩容的 buffer：

```java
ExpandableArrayBuffer buf = new ExpandableArrayBuffer(1024);
buf.putBytes(0, someLongPayload);   // 超过 1024 会自动扩容
```

热路径不推荐，因为可能触发数组拷贝；用于慢路径 / 日志 / 测试。

## 10.7 Atomic 语义

`UnsafeBuffer` 支持 volatile / ordered / release 三种语义的读写，用于无锁数据结构或跨线程 flag：

```java
buf.putIntVolatile(0, 1);       // release + StoreLoad
int v = buf.getIntVolatile(0);  // acquire + LoadLoad
buf.putIntOrdered(0, 2);        // release only（更快）
buf.compareAndSetInt(0, 1, 2);  // CAS
```

Aeron 内部大量用 `putIntOrdered` 做 producer/consumer 同步。

## 10.8 Direct vs Heap 的性能差异

- Direct：Aeron 内部零拷贝写入 term buffer，UDP 时 kernel 也能零拷贝
- Heap：offer 时 driver 必须拷贝到 term buffer

因此发送 buffer 必须 direct。接收 handler 里的 `DirectBuffer` 天然是 direct（term buffer 的一段），你可以直接读，或者 copy 出来到 heap。

## 10.9 测量：JMH 微基准

想验证"复用 UnsafeBuffer 比每次 new 更快"，用 JMH：

```java
@Benchmark
public long directReuse() {
    reusedBuf.putLong(0, ts++);
    return reusedBuf.getLong(0);
}

@Benchmark
public long directNew() {
    UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    b.putLong(0, ts++);
    return b.getLong(0);
}
```

一般能看到 10~50 倍差距 + GC pause 消失。

## 10.10 常见陷阱

1. **wrap 一个太大的 heap 数组**：会 `System.arraycopy` 拷贝到 direct，测出来慢。
2. **多线程共用 UnsafeBuffer**：需要用 Atomic 系列 API 或加锁；`ExclusivePublication` + 自己的 buffer 是最简单的答案。
3. **忘记 flip**：Agrona 的 UnsafeBuffer **没有 position/limit**！你自己控制 offset / length。它不是 java.nio.ByteBuffer。
4. **误以为 heap UnsafeBuffer 更快**：小对象是的，但配 Aeron 还是要 direct。

## 10.11 小结

- UnsafeBuffer 是零 GC 编程的主战场
- 一定要复用 + 预分配
- 下一章讲大消息如何拆包 / 拼包
