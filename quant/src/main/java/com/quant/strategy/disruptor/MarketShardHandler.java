package com.quant.strategy.disruptor;

import com.lmax.disruptor.EventHandler;
import com.quant.common.event.EventType;
import com.quant.common.event.MarketEvent;
import com.quant.common.model.MarketKey;
import com.quant.market.orderbook.OrderBookEngine;
import com.quant.strategy.engine.StrategyEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个 shard 的 Disruptor 消费者。参见设计文档第四节的参考代码。
 * 严格单线程所有权：books / strategies 只能在当前 EventHandler 线程中被修改。
 */
public final class MarketShardHandler implements EventHandler<MarketEvent> {

    private final int shardId;
    private final Map<MarketKey, OrderBookEngine> books = new HashMap<>();
    private final Map<MarketKey, StrategyEngine> strategies = new HashMap<>();

    public MarketShardHandler(int shardId) {
        this.shardId = shardId;
    }

    public void register(StrategyEngine strategy) {
        MarketKey key = strategy.key();
        books.computeIfAbsent(key, OrderBookEngine::new);
        strategies.put(key, strategy);
    }

    public OrderBookEngine bookOf(MarketKey key) {
        return books.get(key);
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) {
        try {
            MarketKey key = event.key();
            if (key == null) return;
            OrderBookEngine book = books.get(key);
            if (book == null) return;

            applyToBook(event, book);

            if (book.isLive()) {
                StrategyEngine strategy = strategies.get(key);
                if (strategy != null) {
                    strategy.onMarketUpdate(book);
                }
            }
        } finally {
            event.recycle();
        }
    }

    private void applyToBook(MarketEvent event, OrderBookEngine book) {
        byte t = event.type();
        if (t == EventType.BBO_UPDATE && event.bbo() != null) {
            var bbo = event.bbo();
            double[] bidPx = { bbo.bid().price() };
            double[] bidSz = { bbo.bid().quantity() };
            double[] askPx = { bbo.ask().price() };
            double[] askSz = { bbo.ask().quantity() };
            book.applySnapshot(bbo.sequence(), bidPx, bidSz, askPx, askSz, event.receiveTimeNs());
        }
    }

    public int shardId() {
        return shardId;
    }
}
