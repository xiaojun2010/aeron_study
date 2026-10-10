package com.quant.strategy.engine;

import com.quant.common.event.OrderIntent;
import com.quant.common.model.MarketKey;
import com.quant.common.model.Side;
import com.quant.market.orderbook.OrderBookEngine;
import com.quant.strategy.quoting.QuoteEngine;
import com.quant.strategy.quoting.TargetQuoteSet;
import com.quant.strategy.reconciler.QuoteReconciler;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 单个 symbol × 单个策略的运行时。
 * 持有：QuoteEngine、Reconciler、本地库存视图、输出 sink。
 * 严格由所属 shard 线程单写者调用。
 */
public final class StrategyEngine {

    private final MarketKey key;
    private final QuoteEngine quoteEngine;
    private final QuoteReconciler reconciler;
    private final LongSupplier clock;
    private final long strategyId;
    private final OrderIntentSink sink;

    private double inventory;

    public StrategyEngine(MarketKey key,
                          QuoteEngine quoteEngine,
                          QuoteReconciler reconciler,
                          LongSupplier clock,
                          long strategyId,
                          OrderIntentSink sink) {
        this.key = key;
        this.quoteEngine = quoteEngine;
        this.reconciler = reconciler;
        this.clock = clock;
        this.strategyId = strategyId;
        this.sink = sink;
    }

    public MarketKey key() {
        return key;
    }

    public void onMarketUpdate(OrderBookEngine book) {
        long now = clock.getAsLong();
        TargetQuoteSet target = quoteEngine.build(key, book.bbo(), inventory, now);
        List<OrderIntent> intents = reconciler.reconcile(target, strategyId, now);
        for (int i = 0, n = intents.size(); i < n; i++) {
            sink.accept(intents.get(i));
        }
    }

    public void onFill(Side side, double qty) {
        inventory += side.sign() * qty;
    }

    public void onReconciledInventory(double absoluteInventory) {
        this.inventory = absoluteInventory;
    }

    public double inventory() {
        return inventory;
    }
}
