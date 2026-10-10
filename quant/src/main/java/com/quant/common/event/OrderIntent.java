package com.quant.common.event;

import com.quant.common.model.MarketKey;
import com.quant.common.model.OrderType;
import com.quant.common.model.Side;

/**
 * QuoteReconciler → Risk → OMS 的下游指令。
 * clientOrderId 已在生成端完成，Risk 不得修改。
 * targetClientOrderId 仅在 CANCEL/AMEND 时填充。
 */
public record OrderIntent(OrderAction action,
                          String clientOrderId,
                          String targetClientOrderId,
                          MarketKey key,
                          Side side,
                          OrderType type,
                          double price,
                          double quantity,
                          long strategyId,
                          long emitTimeNs) {

    public static OrderIntent place(String clientOrderId,
                                    MarketKey key,
                                    Side side,
                                    OrderType type,
                                    double price,
                                    double quantity,
                                    long strategyId,
                                    long emitTimeNs) {
        return new OrderIntent(OrderAction.PLACE, clientOrderId, null,
                key, side, type, price, quantity, strategyId, emitTimeNs);
    }

    public static OrderIntent cancel(String targetClientOrderId,
                                     MarketKey key,
                                     long strategyId,
                                     long emitTimeNs) {
        return new OrderIntent(OrderAction.CANCEL, null, targetClientOrderId,
                key, null, null, 0, 0, strategyId, emitTimeNs);
    }

    public static OrderIntent amend(String clientOrderId,
                                    String targetClientOrderId,
                                    MarketKey key,
                                    Side side,
                                    OrderType type,
                                    double price,
                                    double quantity,
                                    long strategyId,
                                    long emitTimeNs) {
        return new OrderIntent(OrderAction.AMEND, clientOrderId, targetClientOrderId,
                key, side, type, price, quantity, strategyId, emitTimeNs);
    }
}
