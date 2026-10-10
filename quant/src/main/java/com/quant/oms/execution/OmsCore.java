package com.quant.oms.execution;

import com.quant.common.event.ExecutionReport;
import com.quant.common.event.OrderAction;
import com.quant.common.event.OrderIntent;
import com.quant.common.model.Order;
import com.quant.common.model.OrderStatus;
import com.quant.oms.order.OrderStateMachine;
import com.quant.oms.order.OrderStore;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * OMS 核心：下单意图 → 状态机 → 回报去重 → 对账触发。
 *
 * 关键细节：
 *  - 从未得到 ACK 的下单在 send 失败时标记 UNKNOWN，交由 ReconciliationService 查询。
 *  - ExecutionReport 使用 execId 去重，避免乱序/重放造成重复入账。
 */
public final class OmsCore {

    private final OrderStore store;
    private final ExecutionGateway gateway;
    private final LongSupplier clock;
    private final Set<String> seenExecIds = new HashSet<>();

    public OmsCore(OrderStore store, ExecutionGateway gateway, LongSupplier clock) {
        this.store = store;
        this.gateway = gateway;
        this.clock = clock;
    }

    public void onApprovedIntent(OrderIntent intent) {
        long now = clock.getAsLong();
        if (intent.action() == OrderAction.CANCEL) {
            Order existing = store.byClientId(intent.targetClientOrderId());
            if (existing != null && !OrderStateMachine.isTerminal(existing.status())) {
                transition(existing, OrderStatus.PENDING_CANCEL, now);
            }
            gateway.send(intent, new NoopCallback());
            return;
        }

        Order order = new Order(intent.clientOrderId(), intent.key(),
                intent.side(), intent.type(), intent.price(), intent.quantity(), now);
        transition(order, OrderStatus.RISK_APPROVED, now);
        store.put(order);
        transition(order, OrderStatus.PENDING_NEW, now);
        gateway.send(intent, new ExecutionGateway.SendCallback() {
            @Override public void onAccepted(String exchangeOrderId) {
                store.bindExchangeId(order, exchangeOrderId);
                transition(order, OrderStatus.WORKING, clock.getAsLong());
            }
            @Override public void onRejected(String reason) {
                transition(order, OrderStatus.REJECTED, clock.getAsLong());
            }
            @Override public void onUnknown() {
                transition(order, OrderStatus.UNKNOWN, clock.getAsLong());
            }
        });
    }

    public void onExecutionReport(ExecutionReport report) {
        if (!seenExecIds.add(report.execId())) return; // 去重

        Order order = report.exchangeOrderId() != null
                ? store.byExchangeId(report.exchangeOrderId())
                : store.byClientId(report.clientOrderId());
        if (order == null) return;

        long now = clock.getAsLong();
        if (report.lastFillQuantity() > 0) {
            order.applyFill(report.lastFillQuantity(), report.lastFillPrice(), now);
        }
        if (OrderStateMachine.canTransition(order.status(), report.status())) {
            transition(order, report.status(), now);
        }
    }

    private void transition(Order order, OrderStatus next, long nowNs) {
        if (!OrderStateMachine.canTransition(order.status(), next)) {
            // 非法迁移：不要静默丢弃，上线前应接入告警
            return;
        }
        order.transition(next, nowNs);
    }

    private static final class NoopCallback implements ExecutionGateway.SendCallback {
        @Override public void onAccepted(String exchangeOrderId) {}
        @Override public void onRejected(String reason) {}
        @Override public void onUnknown() {}
    }
}
