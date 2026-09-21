# 第 12 章 · Simple Binary Encoding（SBE）

## 学习目标

- 明白 SBE 适合什么、不适合什么；
- 能写一个 SBE schema XML 并生成 Java；
- 学会用 SBE + `tryClaim` 做零拷贝 encoding；
- 认识 SBE 与 Aeron 生态的深度绑定。

## 12.1 SBE 是什么

Simple Binary Encoding 是 FIX 协会牵头的二进制编码标准，Aeron 作者 Martin Thompson 也是核心贡献者。特点：

- **零 GC**：生成的 encoder/decoder 全部基于 DirectBuffer；
- **固定布局**：offset 在编译期确定，读写一条 field 一次内存访问；
- **可扩展**：向后兼容（加字段用 sinceVersion）；
- **schema-first**：XML 描述消息，生成代码。

## 12.2 与 protobuf / Avro 的差别

| 维度 | SBE | protobuf | Avro |
|---|---|---|---|
| 布局 | 固定偏移 | tag + varint | schema resolution |
| GC | 零 | 有对象分配 | 有 |
| 兼容性 | 语义扩展（加字段/枚举） | 强兼容 | 强兼容 |
| schema 分发 | 编译期 | 编译期 | 运行时可选 |
| 适合 | 高频交易、行情 | 通用 RPC | 大数据管道 |

## 12.3 一份 schema 示例

`market-data.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sbe:messageSchema xmlns:sbe="http://fixprotocol.io/2016/sbe"
    package="org.example.sbe" id="1" version="0" byteOrder="littleEndian">

    <types>
        <composite name="messageHeader">
            <type name="blockLength" primitiveType="uint16"/>
            <type name="templateId"  primitiveType="uint16"/>
            <type name="schemaId"    primitiveType="uint16"/>
            <type name="version"     primitiveType="uint16"/>
        </composite>
        <type name="Price" primitiveType="int64" description="8 decimal fixed point"/>
    </types>

    <sbe:message name="MarketTick" id="100" description="行情 tick">
        <field name="timestamp"  id="1" type="int64"/>
        <field name="symbolId"   id="2" type="int32"/>
        <field name="bidPrice"   id="3" type="Price"/>
        <field name="askPrice"   id="4" type="Price"/>
        <field name="bidSize"    id="5" type="int32"/>
        <field name="askSize"    id="6" type="int32"/>
    </sbe:message>
</sbe:messageSchema>
```

## 12.4 代码生成

用 SBE 官方 jar：

```bash
java -jar sbe-all-1.31.1.jar \
  -Dsbe.output.dir=src/main/java \
  -Dsbe.target.language=Java \
  market-data.xml
```

生成 `MarketTickEncoder.java` / `MarketTickDecoder.java` / `MessageHeaderEncoder.java` 等。
Maven 插件版本：`uk.co.real-logic:sbe-tool`。

## 12.5 encoder + Aeron tryClaim（零拷贝黄金组合）

```java
private final ExclusivePublication pub;
private final BufferClaim claim = new BufferClaim();
private final MessageHeaderEncoder header = new MessageHeaderEncoder();
private final MarketTickEncoder    encoder = new MarketTickEncoder();

public void publishTick(long ts, int symbolId, long bid, long ask, int bidSz, int askSz) {
    int length = MessageHeaderEncoder.ENCODED_LENGTH + MarketTickEncoder.BLOCK_LENGTH;

    long r;
    while ((r = pub.tryClaim(length, claim)) < 0) {
        // 处理 offer 返回值
    }

    encoder.wrapAndApplyHeader(claim.buffer(), claim.offset(), header)
           .timestamp(ts)
           .symbolId(symbolId)
           .bidPrice(bid)
           .askPrice(ask)
           .bidSize(bidSz)
           .askSize(askSz);

    claim.commit();
}
```

零拷贝路径：
- 业务字段直接写到 term buffer；
- 无中间 `byte[]`；
- 无 String / Object 分配。

## 12.6 decoder + FragmentHandler

```java
private final MessageHeaderDecoder header = new MessageHeaderDecoder();
private final MarketTickDecoder    decoder = new MarketTickDecoder();

FragmentHandler h = (buffer, offset, length, hdr) -> {
    header.wrap(buffer, offset);
    if (header.templateId() != MarketTickDecoder.TEMPLATE_ID) return;

    decoder.wrap(
        buffer,
        offset + MessageHeaderDecoder.ENCODED_LENGTH,
        header.blockLength(),
        header.version());

    long ts = decoder.timestamp();
    int sid = decoder.symbolId();
    long bid = decoder.bidPrice();
    // ...
};
```

## 12.7 SBE 的进阶特性

- **group**：可变长的重复子结构（用于订单簿深度）
- **varData**：变长字段（symbol 字符串、消息 payload）
- **enum / bitset / composite**：结构化类型
- **presence**：required / optional / constant
- **sinceVersion**：向后兼容加字段

## 12.8 消息 dispatch

一个 stream 上可能有多种 SBE message，用 `templateId` 分发：

```java
switch (header.templateId()) {
    case MarketTickDecoder.TEMPLATE_ID: handleTick(...); break;
    case OrderDecoder.TEMPLATE_ID:      handleOrder(...); break;
    case FillDecoder.TEMPLATE_ID:       handleFill(...); break;
    default: log.warn("未知 templateId={}", header.templateId());
}
```

## 12.9 SBE 在 Aeron 生态

- **Aeron Cluster**：内部所有 protocol message（session connect / vote / append）都是 SBE
- **Aeron Archive**：Control protocol 全是 SBE
- **aeron-cookbook**：几乎所有 sample 都用 SBE

因此 SBE 不是"可选加分项"，而是"深入 Aeron 必学"。

## 12.10 常见陷阱

1. **忘记写 header**：encoder.wrap 不带 header，decoder 就找不到 templateId。
2. **不 commit 直接返回**：tryClaim 后必须 commit / abort。
3. **decoder wrap 用错 blockLength**：跨版本时用 `header.blockLength()` 而不是常量。
4. **varData / group 计算 length**：整体消息长度 = header + block + group + varData，不要固定长度。

## 12.11 小结

- SBE = 零 GC 的二进制协议
- SBE + tryClaim + ExclusivePublication = Aeron 生态最快的发送路径
- Cluster / Archive 都强制用 SBE，早学早受益
