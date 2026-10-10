package com.quant.common.model;

/**
 * Best Bid/Offer 快照。更新戳 updateTimeNs 用于序列一致性/陈旧检测。
 */
public record Bbo(MarketKey key,
                  PriceLevel bid,
                  PriceLevel ask,
                  long sequence,
                  long updateTimeNs) {

    public boolean isCrossed() {
        return !bid.isEmpty() && !ask.isEmpty() && bid.price() >= ask.price();
    }

    public boolean isValid() {
        return !bid.isEmpty() && !ask.isEmpty() && !isCrossed();
    }

    public double midPrice() {
        return (bid.price() + ask.price()) / 2.0;
    }
}
