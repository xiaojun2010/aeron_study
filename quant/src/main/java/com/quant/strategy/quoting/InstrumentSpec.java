package com.quant.strategy.quoting;

/**
 * 交易所合约过滤器的最小集：tickSize、stepSize、最小名义价值。
 * 真实系统还需 maxPrice、maxQty、percent price filter 等。
 */
public record InstrumentSpec(double tickSize,
                             double stepSize,
                             double minNotional,
                             double maxQty) {

    public double alignPrice(double price) {
        if (tickSize <= 0) return price;
        return Math.round(price / tickSize) * tickSize;
    }

    public double floorQuantity(double qty) {
        if (stepSize <= 0) return qty;
        double n = Math.floor(qty / stepSize) * stepSize;
        return Math.min(n, maxQty);
    }

    public boolean passesNotional(double price, double qty) {
        return price * qty >= minNotional;
    }
}
