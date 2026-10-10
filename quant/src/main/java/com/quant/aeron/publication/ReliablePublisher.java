package com.quant.aeron.publication;

import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;

/**
 * 包装 Aeron Publication 并对 offer() 的四种失败分类显式处理。
 *
 * 关键约束（设计文档第五节）：
 *  - BACK_PRESSURED / ADMIN_ACTION：轮询等待，不能静默丢弃
 *  - NOT_CONNECTED：对于订单链路必须返回失败并由上游决定重试/kill switch
 *  - CLOSED / MAX_POSITION_EXCEEDED：异常，由上游处理
 */
public final class ReliablePublisher implements AutoCloseable {

    public enum OfferResult { PUBLISHED, NOT_CONNECTED, CLOSED, MAX_POSITION, FAILED }

    private final Publication publication;
    private final IdleStrategy idleStrategy;
    private final boolean failFastOnDisconnect;

    public ReliablePublisher(Publication publication, IdleStrategy idleStrategy, boolean failFastOnDisconnect) {
        this.publication = publication;
        this.idleStrategy = idleStrategy;
        this.failFastOnDisconnect = failFastOnDisconnect;
    }

    /**
     * 对订单链路使用：遇到 BACK_PRESSURED 持续等待；遇到 NOT_CONNECTED 立即 fail。
     */
    public OfferResult offerReliable(DirectBuffer buffer, int offset, int length) {
        idleStrategy.reset();
        while (true) {
            long result = publication.offer(buffer, offset, length);
            if (result >= 0) return OfferResult.PUBLISHED;
            if (result == Publication.NOT_CONNECTED) {
                return failFastOnDisconnect ? OfferResult.NOT_CONNECTED : waitAndRetry();
            }
            if (result == Publication.CLOSED) return OfferResult.CLOSED;
            if (result == Publication.MAX_POSITION_EXCEEDED) return OfferResult.MAX_POSITION;
            // BACK_PRESSURED or ADMIN_ACTION：空转等待
            idleStrategy.idle();
        }
    }

    private OfferResult waitAndRetry() {
        idleStrategy.idle();
        return OfferResult.NOT_CONNECTED;
    }

    /**
     * 对可丢弃的行情链路使用：遇到背压返回 FAILED，由上游决定是否降采样。
     */
    public OfferResult offerBestEffort(DirectBuffer buffer, int offset, int length) {
        long result = publication.offer(buffer, offset, length);
        if (result >= 0) return OfferResult.PUBLISHED;
        if (result == Publication.NOT_CONNECTED) return OfferResult.NOT_CONNECTED;
        if (result == Publication.CLOSED) return OfferResult.CLOSED;
        if (result == Publication.MAX_POSITION_EXCEEDED) return OfferResult.MAX_POSITION;
        return OfferResult.FAILED;
    }

    public boolean isConnected() {
        return publication.isConnected();
    }

    @Override
    public void close() {
        publication.close();
    }
}
