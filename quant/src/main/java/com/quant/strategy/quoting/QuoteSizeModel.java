package com.quant.strategy.quoting;

/**
 * 根据当前库存等级在 base/min 之间插值挂单量。
 * 库存越偏离 0，异侧挂量越大、同侧挂量越小。
 */
public final class QuoteSizeModel {

    private final double baseSize;
    private final double minSize;
    private final double maxInventory;

    public QuoteSizeModel(double baseSize, double minSize, double maxInventory) {
        this.baseSize = baseSize;
        this.minSize = minSize;
        this.maxInventory = Math.max(1e-9, maxInventory);
    }

    public double bidSize(double inventory) {
        double skew = Math.max(0, inventory) / maxInventory;
        return clamp(baseSize * (1 - skew));
    }

    public double askSize(double inventory) {
        double skew = Math.max(0, -inventory) / maxInventory;
        return clamp(baseSize * (1 - skew));
    }

    private double clamp(double s) {
        return Math.max(minSize, Math.min(baseSize, s));
    }
}
