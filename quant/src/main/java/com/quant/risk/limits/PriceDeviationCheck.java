package com.quant.risk.limits;

import com.quant.common.model.Bbo;

/**
 * 价格合理性校验：相对最新 BBO 的最大偏离百分比。
 * 防止策略发出错误价（如 FairPrice 计算 bug 导致远离市价下单）。
 */
public final class PriceDeviationCheck {

    private final double maxDeviationBps;

    public PriceDeviationCheck(double maxDeviationBps) {
        this.maxDeviationBps = maxDeviationBps;
    }

    public boolean passes(double orderPrice, Bbo referenceBbo) {
        if (referenceBbo == null || !referenceBbo.isValid()) return false;
        double ref = referenceBbo.midPrice();
        double deviationBps = Math.abs(orderPrice - ref) / ref * 10_000.0;
        return deviationBps <= maxDeviationBps;
    }
}
