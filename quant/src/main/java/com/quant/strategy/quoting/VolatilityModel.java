package com.quant.strategy.quoting;

/**
 * EWMA（指数加权）短期收益率波动估计。
 * 在策略 shard 内单写者更新；不保证跨 shard 一致性。
 */
public final class VolatilityModel {

    private final double alpha;
    private double lastPrice = Double.NaN;
    private double variance;

    public VolatilityModel(double alpha) {
        if (alpha <= 0 || alpha >= 1) throw new IllegalArgumentException("alpha not in (0,1)");
        this.alpha = alpha;
    }

    public double update(double price) {
        if (Double.isNaN(lastPrice)) {
            lastPrice = price;
            return 0.0;
        }
        double ret = Math.log(price / lastPrice);
        variance = alpha * ret * ret + (1 - alpha) * variance;
        lastPrice = price;
        return Math.sqrt(variance);
    }

    public double currentVolatility() {
        return Math.sqrt(variance);
    }
}
