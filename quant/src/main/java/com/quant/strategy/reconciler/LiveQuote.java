package com.quant.strategy.reconciler;

import com.quant.common.model.MarketKey;
import com.quant.common.model.Side;

/**
 * 当前已在交易所存活的策略挂单的本地视图。
 * clientOrderId 是幂等 key；由 OMS 回传的 WORKING 状态驱动。
 */
public final class LiveQuote {

    private final String clientOrderId;
    private final MarketKey key;
    private final Side side;
    private double price;
    private double quantity;

    public LiveQuote(String clientOrderId, MarketKey key, Side side, double price, double quantity) {
        this.clientOrderId = clientOrderId;
        this.key = key;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
    }

    public String clientOrderId() { return clientOrderId; }
    public MarketKey key() { return key; }
    public Side side() { return side; }
    public double price() { return price; }
    public double quantity() { return quantity; }

    public void updatePriceQty(double price, double quantity) {
        this.price = price;
        this.quantity = quantity;
    }
}
