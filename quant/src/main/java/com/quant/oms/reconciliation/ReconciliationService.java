package com.quant.oms.reconciliation;

import com.quant.common.model.Order;
import com.quant.common.model.OrderStatus;
import com.quant.oms.order.OrderStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理设计文档第六节的 UNKNOWN 状态：
 *  - 枚举所有 UNKNOWN 订单
 *  - 调用交易所 OpenOrders + 近期成交查询
 *  - 根据返回结果把订单推进到正确状态
 *
 * 真实实现需要具体 ExchangeConnector 的 queryOrder/queryOpenOrders API。
 * 这里给出调度骨架；具体查询由注入的 Reconciler lambda 完成。
 */
public final class ReconciliationService {

    public interface ExchangeOrderQuery {
        /**
         * 返回交易所视角下的订单状态。无法确认时返回 null，调用方应保留 UNKNOWN。
         */
        ReconciledOrder lookup(Order local);
    }

    public record ReconciledOrder(OrderStatus status,
                                  double cumFilled,
                                  double avgFillPrice,
                                  String exchangeOrderId) {}

    private final OrderStore store;
    private final ExchangeOrderQuery query;

    public ReconciliationService(OrderStore store, ExchangeOrderQuery query) {
        this.store = store;
        this.query = query;
    }

    /** 由调度器定期调用；返回已完成对账的订单列表（供上游下线 UNKNOWN）。 */
    public List<Order> reconcileOnce(long nowNs) {
        List<Order> reconciled = new ArrayList<>();
        for (Order o : new ArrayList<>(store.allLive())) {
            if (o.status() != OrderStatus.UNKNOWN) continue;
            ReconciledOrder result = query.lookup(o);
            if (result == null) continue;
            if (result.exchangeOrderId() != null && o.exchangeOrderId() == null) {
                store.bindExchangeId(o, result.exchangeOrderId());
            }
            o.transition(result.status(), nowNs);
            reconciled.add(o);
        }
        return reconciled;
    }
}
