package com.quant.strategy.quoting;

/**
 * 价差模型：基础 spread + 波动率溢价。
 * 真实实现常会进一步根据流动性/对手盘毒性/历史成交质量调整。
 */
public final class SpreadModel {

    private final double baseSpreadBps;
    private final double volatilityMultiplier;
    private final double minSpread;

    public SpreadModel(double baseSpreadBps, double volatilityMultiplier, double minSpread) {
        this.baseSpreadBps = baseSpreadBps;
        this.volatilityMultiplier = volatilityMultiplier;
        this.minSpread = minSpread;
    }

    public double compute(double referencePrice, double volatility) {
        double baseAbsolute = referencePrice * baseSpreadBps / 10_000.0;
        double volComponent = referencePrice * volatility * volatilityMultiplier;
        return Math.max(minSpread, baseAbsolute + volComponent);
    }
}
