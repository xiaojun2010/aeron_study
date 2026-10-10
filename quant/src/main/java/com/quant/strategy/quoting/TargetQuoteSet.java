package com.quant.strategy.quoting;

import com.quant.common.model.MarketKey;

/**
 * 一次报价周期的目标双边挂单。
 * 价格已经做过 tickSize 对齐，数量已检查 minSize/maxSize。
 */
public record TargetQuoteSet(MarketKey key,
                             double bidPrice,
                             double bidQuantity,
                             double askPrice,
                             double askQuantity,
                             long emitTimeNs) {

    public boolean hasBid() {
        return bidQuantity > 0 && !Double.isNaN(bidPrice);
    }

    public boolean hasAsk() {
        return askQuantity > 0 && !Double.isNaN(askPrice);
    }

    public boolean isEmpty() {
        return !hasBid() && !hasAsk();
    }
}
