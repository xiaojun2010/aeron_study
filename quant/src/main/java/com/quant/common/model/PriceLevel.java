package com.quant.common.model;

/**
 * 单边订单簿一个价位的聚合视图。
 * price 使用 double 仅为示例；真实系统应用定点整数（minTick 的倍数）避免浮点误差。
 */
public record PriceLevel(double price, double quantity) {

    public static final PriceLevel EMPTY = new PriceLevel(Double.NaN, 0.0);

    public boolean isEmpty() {
        return quantity <= 0.0 || Double.isNaN(price);
    }
}
