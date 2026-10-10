package com.quant.risk.pretrade;

import com.quant.common.event.OrderAction;
import com.quant.common.event.OrderIntent;
import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.risk.killswitch.KillSwitch;
import com.quant.risk.limits.AccountLimits;
import com.quant.risk.limits.PriceDeviationCheck;

import java.util.HashMap;
import java.util.Map;

/**
 * 单一检查入口。所有 OrderIntent 必须流经该引擎（设计文档第八节）。
 * 当前实现：Kill Switch → RateLimit → Notional → Exposure → PriceDeviation。
 * 顺序刻意：越便宜越早。
 */
public final class PreTradeRiskEngine {

    private final KillSwitch killSwitch;
    private final AccountLimits accountLimits;
    private final PriceDeviationCheck priceCheck;
    private final Map<MarketKey, Bbo> latestBbo = new HashMap<>();

    public PreTradeRiskEngine(KillSwitch killSwitch,
                              AccountLimits accountLimits,
                              PriceDeviationCheck priceCheck) {
        this.killSwitch = killSwitch;
        this.accountLimits = accountLimits;
        this.priceCheck = priceCheck;
    }

    public void updateBbo(Bbo bbo) {
        latestBbo.put(bbo.key(), bbo);
    }

    public RiskDecision check(OrderIntent intent) {
        if (killSwitch.isEngaged()) {
            KillSwitch.Reason r = killSwitch.reason();
            return RiskDecision.reject("KILL_SWITCH", r == null ? "engaged" : r.code());
        }
        if (intent.action() == OrderAction.CANCEL) {
            return RiskDecision.approve();
        }
        if (!accountLimits.checkRate(intent.emitTimeNs())) {
            return RiskDecision.reject("RATE_LIMIT", "orders/sec exceeded");
        }
        double notional = intent.price() * intent.quantity();
        if (notional > accountLimits.maxOrderNotional()) {
            return RiskDecision.reject("MAX_NOTIONAL", "notional=" + notional);
        }
        double signedDelta = intent.side().sign() * intent.quantity() * intent.price();
        if (!accountLimits.checkExposure(signedDelta)) {
            return RiskDecision.reject("MAX_EXPOSURE", "exposure limit");
        }
        Bbo ref = latestBbo.get(intent.key());
        if (!priceCheck.passes(intent.price(), ref)) {
            return RiskDecision.reject("PRICE_DEVIATION", "bbo-check failed");
        }
        accountLimits.applyExposure(signedDelta);
        return RiskDecision.approve();
    }
}
