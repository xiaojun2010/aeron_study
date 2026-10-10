package com.quant.common.event;

import com.quant.common.model.MarketKey;
import com.quant.common.model.OrderStatus;
import com.quant.common.model.Side;

/**
 * 交易所成交回报（已标准化）。
 * execId 用于 OMS 去重；缺口通过本地序列号/exchangeOrderId 比对。
 */
public record ExecutionReport(String execId,
                              String clientOrderId,
                              String exchangeOrderId,
                              MarketKey key,
                              Side side,
                              OrderStatus status,
                              double lastFillQuantity,
                              double lastFillPrice,
                              double cumFilledQuantity,
                              double leavesQuantity,
                              long exchangeTimeNs,
                              long receiveTimeNs,
                              String rejectReason) {
}
