package com.quant.risk.limits;

/**
 * 账户级可消耗额度：净敞口上限、单笔最大名义、每秒最大报单数。
 * 真实系统应加入分策略/分交易对二维限额。
 */
public final class AccountLimits {

    private final double maxNetExposure;
    private final double maxOrderNotional;
    private final int maxOrdersPerSecond;

    private double currentExposure;
    private long windowStartNs;
    private int ordersInWindow;

    public AccountLimits(double maxNetExposure, double maxOrderNotional, int maxOrdersPerSecond) {
        this.maxNetExposure = maxNetExposure;
        this.maxOrderNotional = maxOrderNotional;
        this.maxOrdersPerSecond = maxOrdersPerSecond;
    }

    public double maxOrderNotional() { return maxOrderNotional; }
    public double maxNetExposure() { return maxNetExposure; }

    public boolean checkRate(long nowNs) {
        if (nowNs - windowStartNs > 1_000_000_000L) {
            windowStartNs = nowNs;
            ordersInWindow = 0;
        }
        if (ordersInWindow >= maxOrdersPerSecond) {
            return false;
        }
        ordersInWindow++;
        return true;
    }

    public boolean checkExposure(double proposedDelta) {
        return Math.abs(currentExposure + proposedDelta) <= maxNetExposure;
    }

    public void applyExposure(double delta) {
        currentExposure += delta;
    }

    public double currentExposure() {
        return currentExposure;
    }
}
