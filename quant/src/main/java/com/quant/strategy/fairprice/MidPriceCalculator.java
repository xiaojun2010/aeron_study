package com.quant.strategy.fairprice;

import com.quant.common.model.Bbo;

/**
 * MidPrice = (BestBid + BestAsk) / 2
 */
public final class MidPriceCalculator implements FairPriceCalculator {

    @Override
    public double compute(Bbo bbo) {
        if (!bbo.isValid()) return Double.NaN;
        return (bbo.bid().price() + bbo.ask().price()) / 2.0;
    }

    @Override
    public String name() {
        return "MID";
    }
}
