package com.quant.oms.order;

import com.quant.common.model.OrderStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * OMS 状态机的合法迁移表。
 * UNKNOWN 可以从任何状态进入（上游断链时），退出仅通过显式对账。
 *
 * 这里刻意把合法迁移写死而不是用通配——非法迁移在真实系统里几乎总是 bug。
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, EnumSet<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(OrderStatus.NEW_INTENT, EnumSet.of(OrderStatus.RISK_APPROVED, OrderStatus.REJECTED));
        ALLOWED.put(OrderStatus.RISK_APPROVED, EnumSet.of(OrderStatus.PENDING_NEW, OrderStatus.REJECTED));
        ALLOWED.put(OrderStatus.PENDING_NEW, EnumSet.of(OrderStatus.WORKING, OrderStatus.REJECTED, OrderStatus.UNKNOWN));
        ALLOWED.put(OrderStatus.WORKING, EnumSet.of(
                OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                OrderStatus.PENDING_CANCEL, OrderStatus.CANCELED, OrderStatus.UNKNOWN));
        ALLOWED.put(OrderStatus.PARTIALLY_FILLED, EnumSet.of(
                OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                OrderStatus.PENDING_CANCEL, OrderStatus.CANCELED, OrderStatus.UNKNOWN));
        ALLOWED.put(OrderStatus.PENDING_CANCEL, EnumSet.of(
                OrderStatus.CANCELED, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.UNKNOWN));
        ALLOWED.put(OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.UNKNOWN, EnumSet.allOf(OrderStatus.class));
    }

    private OrderStateMachine() {}

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == to && (from == OrderStatus.PARTIALLY_FILLED)) return true;
        EnumSet<OrderStatus> allowed = ALLOWED.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED
                || status == OrderStatus.REJECTED;
    }
}
