package com.quant.oms.order;

import com.quant.common.model.Order;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * clientOrderId / exchangeOrderId 双向映射的订单仓库。
 * 单写者语义——由 OMS 主线程独占访问。
 */
public final class OrderStore {

    private final Map<String, Order> byClientId = new HashMap<>();
    private final Map<String, Order> byExchangeId = new HashMap<>();

    public void put(Order order) {
        Order prev = byClientId.put(order.clientOrderId(), order);
        if (prev != null && !prev.clientOrderId().equals(order.clientOrderId())) {
            throw new IllegalStateException("duplicate clientOrderId: " + order.clientOrderId());
        }
    }

    public Order byClientId(String id) {
        return byClientId.get(id);
    }

    public Order byExchangeId(String id) {
        return id == null ? null : byExchangeId.get(id);
    }

    public void bindExchangeId(Order order, String exchangeOrderId) {
        if (exchangeOrderId == null) return;
        order.bindExchangeOrderId(exchangeOrderId);
        byExchangeId.put(exchangeOrderId, order);
    }

    public Collection<Order> allLive() {
        return byClientId.values();
    }

    public void remove(Order order) {
        byClientId.remove(order.clientOrderId());
        if (order.exchangeOrderId() != null) {
            byExchangeId.remove(order.exchangeOrderId());
        }
    }
}
