package com.quant.common.event;

import com.quant.common.model.MarketKey;
import com.quant.common.model.Side;

/**
 * 策略发出的"想要存在"的报价。由 QuoteReconciler 转换成 PLACE/CANCEL/AMEND 原语。
 */
public record QuoteIntent(MarketKey key,
                          Side side,
                          double price,
                          double quantity,
                          long strategyId,
                          long emitTimeNs) {
}
