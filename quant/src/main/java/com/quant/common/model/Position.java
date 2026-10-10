package com.quant.common.model;

/**
 * 账户级净仓位（含加权持仓成本）。
 * 跨 Symbol 的账户视图应有唯一所有者线程，参见设计文档第四节的边界说明。
 */
public final class Position {

    private final MarketKey key;
    private double netQuantity;
    private double avgEntryPrice;
    private double realizedPnl;

    public Position(MarketKey key) {
        this.key = key;
    }

    public MarketKey key() { return key; }
    public double netQuantity() { return netQuantity; }
    public double avgEntryPrice() { return avgEntryPrice; }
    public double realizedPnl() { return realizedPnl; }

    public void applyFill(Side side, double qty, double price) {
        double signed = side.sign() * qty;
        double newQty = netQuantity + signed;
        if (netQuantity == 0 || Math.signum(netQuantity) == Math.signum(signed)) {
            double newNotional = avgEntryPrice * Math.abs(netQuantity) + price * qty;
            avgEntryPrice = Math.abs(newQty) == 0 ? 0 : newNotional / Math.abs(newQty);
        } else {
            double closedQty = Math.min(Math.abs(netQuantity), qty);
            double pnlPerUnit = (price - avgEntryPrice) * Math.signum(netQuantity);
            realizedPnl += pnlPerUnit * closedQty;
            if (Math.abs(signed) > Math.abs(netQuantity)) {
                avgEntryPrice = price;
            }
        }
        netQuantity = newQty;
    }
}
