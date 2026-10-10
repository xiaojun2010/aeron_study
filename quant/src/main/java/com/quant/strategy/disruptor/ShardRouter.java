package com.quant.strategy.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.quant.common.event.MarketEvent;
import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.common.model.Trade;

import java.util.Objects;

/**
 * MarketEvent 分发器：按 Objects.hash(exchange, symbol) % shardCount 路由。
 * 对应设计文档第四节的分片规则。
 */
public final class ShardRouter {

    private final RingBuffer<MarketEvent>[] ringBuffers;
    private final Disruptor<MarketEvent>[] disruptors;

    @SafeVarargs
    public ShardRouter(Disruptor<MarketEvent>... disruptors) {
        this.disruptors = disruptors;
        this.ringBuffers = new RingBuffer[disruptors.length];
        for (int i = 0; i < disruptors.length; i++) {
            this.ringBuffers[i] = disruptors[i].getRingBuffer();
        }
    }

    public int shardFor(MarketKey key) {
        return Math.floorMod(Objects.hash(key.exchange(), key.symbol()), ringBuffers.length);
    }

    public void publishBbo(Bbo bbo, long receiveNs) {
        RingBuffer<MarketEvent> rb = ringBuffers[shardFor(bbo.key())];
        long seq = rb.next();
        try {
            MarketEvent slot = rb.get(seq);
            slot.asBbo(bbo, receiveNs);
        } finally {
            rb.publish(seq);
        }
    }

    public void publishTrade(Trade trade, long receiveNs) {
        RingBuffer<MarketEvent> rb = ringBuffers[shardFor(trade.key())];
        long seq = rb.next();
        try {
            MarketEvent slot = rb.get(seq);
            slot.asTrade(trade, receiveNs);
        } finally {
            rb.publish(seq);
        }
    }

    public int shardCount() {
        return ringBuffers.length;
    }

    public void shutdown() {
        for (Disruptor<MarketEvent> d : disruptors) {
            d.shutdown();
        }
    }
}
