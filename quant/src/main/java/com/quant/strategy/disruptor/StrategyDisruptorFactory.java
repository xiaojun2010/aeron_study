package com.quant.strategy.disruptor;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.quant.common.event.MarketEvent;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 策略引擎 Disruptor 工厂。
 * 默认 SINGLE producer（Gateway 单线程写入），每 shard 独立 ring buffer。
 */
public final class StrategyDisruptorFactory {

    private final int shardCount;
    private final int ringSize;
    private final boolean busySpin;

    public StrategyDisruptorFactory(int shardCount, int ringSize, boolean busySpin) {
        if (Integer.bitCount(ringSize) != 1) {
            throw new IllegalArgumentException("ringSize must be a power of 2");
        }
        this.shardCount = shardCount;
        this.ringSize = ringSize;
        this.busySpin = busySpin;
    }

    public Disruptor<MarketEvent>[] build(MarketShardHandler[] handlers) {
        if (handlers.length != shardCount) {
            throw new IllegalArgumentException("handlers count != shardCount");
        }
        @SuppressWarnings("unchecked")
        Disruptor<MarketEvent>[] disruptors = new Disruptor[shardCount];
        AtomicInteger id = new AtomicInteger();
        for (int i = 0; i < shardCount; i++) {
            final int shard = i;
            WaitStrategy wait = busySpin ? new BusySpinWaitStrategy() : new YieldingWaitStrategy();
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "shard-" + shard + "-worker-" + id.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
            Disruptor<MarketEvent> d = new Disruptor<>(
                    MarketEvent::new,
                    ringSize,
                    tf,
                    ProducerType.SINGLE,
                    wait);
            d.handleEventsWith(handlers[i]);
            disruptors[i] = d;
        }
        return disruptors;
    }
}
