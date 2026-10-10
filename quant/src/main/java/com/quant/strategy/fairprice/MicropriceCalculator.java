package com.quant.strategy.fairprice;

import com.quant.common.model.Bbo;

/**
 * Microprice = (Ask·BidQty + Bid·AskQty) / (BidQty + AskQty)
 * 对应设计文档 3.1 节。表示短期订单簿压力下的隐含价格。
 */
public final class MicropriceCalculator implements FairPriceCalculator {

    @Override
    public double compute(Bbo bbo) {
        if (!bbo.isValid()) return Double.NaN;
        double bidPx = bbo.bid().price();
        double askPx = bbo.ask().price();
        double bidQ = bbo.bid().quantity();
        double askQ = bbo.ask().quantity();
        double denom = bidQ + askQ;
        if (denom <= 0) return (bidPx + askPx) / 2.0;
        return (askPx * bidQ + bidPx * askQ) / denom;
    }

    @Override
    public String name() {
        return "MICROPRICE";
    }
}
