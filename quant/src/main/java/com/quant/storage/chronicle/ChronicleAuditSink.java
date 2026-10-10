package com.quant.storage.chronicle;

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

import java.nio.file.Path;

/**
 * 旁路审计 sink。关键事件（OrderIntent / ExecutionReport / RiskDecision）落盘，
 * 供回放和事后对账使用。参见设计文档第二节的旁路通道。
 *
 * 单写者：由单一线程调用 append；多路事件由上游合并后再写入。
 */
public final class ChronicleAuditSink implements AutoCloseable {

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;

    public ChronicleAuditSink(Path dir) {
        this.queue = SingleChronicleQueueBuilder.binary(dir.toFile()).build();
        this.appender = queue.acquireAppender();
    }

    public void append(byte eventType, byte[] payload) {
        appender.writeDocument(w -> w.getValueOut()
                .marshallable(m -> m.write("t").int8(eventType)
                        .write("p").bytes(payload)));
    }

    @Override
    public void close() {
        queue.close();
    }
}
