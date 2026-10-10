package com.quant.strategy.fairprice;

import com.quant.common.model.Bbo;

/**
 * 公允价格策略接口。实现包括 MidPrice、Microprice、VWAP 等。
 * 热路径：接口只传入 BBO，避免复杂对象分配。
 */
public interface FairPriceCalculator {

    double compute(Bbo bbo);

    String name();
}
