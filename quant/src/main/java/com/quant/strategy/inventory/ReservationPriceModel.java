package com.quant.strategy.inventory;

/**
 * ReservationPrice = FairPrice - gamma * q * sigma^2 * T
 * 对应设计文档 3.2 节 Avellaneda-Stoikov 风格的简化模型。
 *
 * 注意：真实系统必须自己校准 gamma / sigma / T 单位，并且加入库存硬边界。
 */
public final class ReservationPriceModel {

    private final double gamma;
    private final double timeHorizon;

    public ReservationPriceModel(double gamma, double timeHorizon) {
        if (gamma < 0) throw new IllegalArgumentException("gamma<0");
        if (timeHorizon <= 0) throw new IllegalArgumentException("T<=0");
        this.gamma = gamma;
        this.timeHorizon = timeHorizon;
    }

    public double compute(double fairPrice, double netInventory, double volatility) {
        if (Double.isNaN(fairPrice)) return Double.NaN;
        double sigmaSquared = volatility * volatility;
        return fairPrice - gamma * netInventory * sigmaSquared * timeHorizon;
    }
}
