package com.quant.common.model;

/**
 * 已被 OMS 跟踪的订单（含交易所分配的 exchangeOrderId）。
 * clientOrderId 是幂等键，禁止被重用。
 */
public final class Order {

    private final String clientOrderId;
    private final MarketKey key;
    private final Side side;
    private final OrderType type;
    private final double price;
    private final double quantity;
    private final long createTimeNs;

    private volatile String exchangeOrderId;
    private volatile OrderStatus status;
    private volatile double filledQuantity;
    private volatile double avgFillPrice;
    private volatile long lastUpdateTimeNs;

    public Order(String clientOrderId,
                 MarketKey key,
                 Side side,
                 OrderType type,
                 double price,
                 double quantity,
                 long createTimeNs) {
        this.clientOrderId = clientOrderId;
        this.key = key;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.createTimeNs = createTimeNs;
        this.status = OrderStatus.NEW_INTENT;
        this.lastUpdateTimeNs = createTimeNs;
    }

    public String clientOrderId() { return clientOrderId; }
    public MarketKey key() { return key; }
    public Side side() { return side; }
    public OrderType type() { return type; }
    public double price() { return price; }
    public double quantity() { return quantity; }
    public long createTimeNs() { return createTimeNs; }

    public String exchangeOrderId() { return exchangeOrderId; }
    public OrderStatus status() { return status; }
    public double filledQuantity() { return filledQuantity; }
    public double avgFillPrice() { return avgFillPrice; }
    public long lastUpdateTimeNs() { return lastUpdateTimeNs; }

    public double remainingQuantity() {
        return Math.max(0.0, quantity - filledQuantity);
    }

    public void bindExchangeOrderId(String exchangeOrderId) {
        this.exchangeOrderId = exchangeOrderId;
    }

    public void transition(OrderStatus next, long nowNs) {
        this.status = next;
        this.lastUpdateTimeNs = nowNs;
    }

    public void applyFill(double fillQty, double fillPx, long nowNs) {
        double newFilled = this.filledQuantity + fillQty;
        if (newFilled > quantity + 1e-9) {
            throw new IllegalStateException("overfill: cum=" + newFilled + " qty=" + quantity);
        }
        double newNotional = this.avgFillPrice * this.filledQuantity + fillPx * fillQty;
        this.filledQuantity = newFilled;
        this.avgFillPrice = newFilled == 0 ? 0 : newNotional / newFilled;
        this.lastUpdateTimeNs = nowNs;
    }
}
