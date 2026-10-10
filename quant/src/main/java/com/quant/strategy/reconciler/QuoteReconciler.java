package com.quant.strategy.reconciler;

import com.quant.common.event.OrderIntent;
import com.quant.common.model.MarketKey;
import com.quant.common.model.OrderType;
import com.quant.common.model.Side;
import com.quant.strategy.quoting.TargetQuoteSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 差分器：把 TargetQuoteSet 与当前 Live 挂单比对，产出 PLACE/CANCEL/AMEND。
 *
 * 关键决策：
 *  - 价格或数量超过阈值变化 → AMEND（如交易所不支持 amend 则 fallback 为 cancel+place）
 *  - 目标为空而本地有挂单 → CANCEL
 *  - 本地没有挂单且目标有效 → PLACE
 */
public final class QuoteReconciler {

    private final double priceTolerance;
    private final double qtyTolerance;
    private final ClientOrderIdFactory ids;
    private final boolean supportsAmend;

    private final Map<MarketKey, LiveQuote> liveBids = new HashMap<>();
    private final Map<MarketKey, LiveQuote> liveAsks = new HashMap<>();

    public QuoteReconciler(double priceTolerance,
                           double qtyTolerance,
                           ClientOrderIdFactory ids,
                           boolean supportsAmend) {
        this.priceTolerance = priceTolerance;
        this.qtyTolerance = qtyTolerance;
        this.ids = ids;
        this.supportsAmend = supportsAmend;
    }

    public List<OrderIntent> reconcile(TargetQuoteSet target, long strategyId, long nowNs) {
        List<OrderIntent> out = new ArrayList<>(4);
        reconcileSide(target.key(), Side.BUY,
                target.hasBid() ? target.bidPrice() : Double.NaN,
                target.hasBid() ? target.bidQuantity() : 0,
                liveBids, out, strategyId, nowNs);
        reconcileSide(target.key(), Side.SELL,
                target.hasAsk() ? target.askPrice() : Double.NaN,
                target.hasAsk() ? target.askQuantity() : 0,
                liveAsks, out, strategyId, nowNs);
        return out;
    }

    private void reconcileSide(MarketKey key,
                               Side side,
                               double targetPx,
                               double targetQty,
                               Map<MarketKey, LiveQuote> live,
                               List<OrderIntent> out,
                               long strategyId,
                               long nowNs) {
        LiveQuote current = live.get(key);
        boolean wantQuote = targetQty > 0 && !Double.isNaN(targetPx);

        if (!wantQuote) {
            if (current != null) {
                out.add(OrderIntent.cancel(current.clientOrderId(), key, strategyId, nowNs));
                live.remove(key);
            }
            return;
        }

        if (current == null) {
            String newId = ids.next();
            out.add(OrderIntent.place(newId, key, side, OrderType.POST_ONLY,
                    targetPx, targetQty, strategyId, nowNs));
            live.put(key, new LiveQuote(newId, key, side, targetPx, targetQty));
            return;
        }

        boolean priceDrift = Math.abs(current.price() - targetPx) > priceTolerance;
        boolean qtyDrift = Math.abs(current.quantity() - targetQty) > qtyTolerance;
        if (!priceDrift && !qtyDrift) return;

        if (supportsAmend) {
            String newId = ids.next();
            out.add(OrderIntent.amend(newId, current.clientOrderId(), key, side, OrderType.POST_ONLY,
                    targetPx, targetQty, strategyId, nowNs));
            current.updatePriceQty(targetPx, targetQty);
        } else {
            out.add(OrderIntent.cancel(current.clientOrderId(), key, strategyId, nowNs));
            String newId = ids.next();
            out.add(OrderIntent.place(newId, key, side, OrderType.POST_ONLY,
                    targetPx, targetQty, strategyId, nowNs));
            live.put(key, new LiveQuote(newId, key, side, targetPx, targetQty));
        }
    }

    /** 来自 OMS 的 WORKING 回报：建立/校正本地视图。 */
    public void onWorking(MarketKey key, Side side, String clientOrderId, double price, double qty) {
        Map<MarketKey, LiveQuote> live = side == Side.BUY ? liveBids : liveAsks;
        live.put(key, new LiveQuote(clientOrderId, key, side, price, qty));
    }

    /** 来自 OMS 的 CANCELED / FILLED / REJECTED 回报：清除本地视图。 */
    public void onInactive(MarketKey key, Side side, String clientOrderId) {
        Map<MarketKey, LiveQuote> live = side == Side.BUY ? liveBids : liveAsks;
        LiveQuote q = live.get(key);
        if (q != null && q.clientOrderId().equals(clientOrderId)) {
            live.remove(key);
        }
    }
}
