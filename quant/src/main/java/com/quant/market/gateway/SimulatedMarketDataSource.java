package com.quant.market.gateway;

import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.common.model.PriceLevel;
import com.quant.common.util.NanoClock;
import com.quant.exchange.common.MarketListener;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地模拟行情源：生成周期性 BBO。
 * 用途：集成测试、Bootstrap 自测、延迟探针，不接入真实交易所。
 */
public final class SimulatedMarketDataSource {

    private final List<MarketKey> symbols;
    private final NanoClock clock;
    private final long intervalMicros;
    private final ScheduledExecutorService executor;
    private final AtomicLong sequence = new AtomicLong();

    private MarketListener listener;
    private double basePrice = 100.0;

    public SimulatedMarketDataSource(List<MarketKey> symbols, NanoClock clock, long intervalMicros) {
        this.symbols = symbols;
        this.clock = clock;
        this.intervalMicros = intervalMicros;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-marketdata");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(MarketListener listener) {
        this.listener = listener;
        executor.scheduleAtFixedRate(this::tick, 0, intervalMicros, TimeUnit.MICROSECONDS);
    }

    private void tick() {
        if (listener == null) return;
        long now = clock.nanoTime();
        for (MarketKey key : symbols) {
            double drift = Math.sin(sequence.get() / 100.0) * 0.5;
            double bid = basePrice + drift - 0.05;
            double ask = basePrice + drift + 0.05;
            Bbo bbo = new Bbo(key,
                    new PriceLevel(bid, 20.0),
                    new PriceLevel(ask, 20.0),
                    sequence.incrementAndGet(),
                    now);
            listener.onBbo(bbo, now);
        }
    }

    public void stop() {
        executor.shutdownNow();
    }
}
