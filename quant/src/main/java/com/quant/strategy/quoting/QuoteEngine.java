package com.quant.strategy.quoting;

import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.strategy.fairprice.FairPriceCalculator;
import com.quant.strategy.inventory.ReservationPriceModel;

/**
 * 把 fair-price + 库存 + 波动率 + spread 合成为目标双边报价。
 * 设计文档 3.3 节：Bid = Reservation - spread/2, Ask = Reservation + spread/2
 */
public final class QuoteEngine {

    private final FairPriceCalculator fairPrice;
    private final ReservationPriceModel reservation;
    private final SpreadModel spreadModel;
    private final QuoteSizeModel sizeModel;
    private final VolatilityModel volatilityModel;
    private final InstrumentSpec spec;

    public QuoteEngine(FairPriceCalculator fairPrice,
                       ReservationPriceModel reservation,
                       SpreadModel spreadModel,
                       QuoteSizeModel sizeModel,
                       VolatilityModel volatilityModel,
                       InstrumentSpec spec) {
        this.fairPrice = fairPrice;
        this.reservation = reservation;
        this.spreadModel = spreadModel;
        this.sizeModel = sizeModel;
        this.volatilityModel = volatilityModel;
        this.spec = spec;
    }

    public TargetQuoteSet build(MarketKey key, Bbo bbo, double inventory, long nowNs) {
        if (!bbo.isValid()) return emptyTarget(key, nowNs);

        double fair = fairPrice.compute(bbo);
        if (Double.isNaN(fair)) return emptyTarget(key, nowNs);

        double vol = volatilityModel.update(fair);
        double reservationPrice = reservation.compute(fair, inventory, vol);
        double spread = spreadModel.compute(reservationPrice, vol);

        double bidPx = spec.alignPrice(reservationPrice - spread / 2.0);
        double askPx = spec.alignPrice(reservationPrice + spread / 2.0);

        double bidQty = spec.floorQuantity(sizeModel.bidSize(inventory));
        double askQty = spec.floorQuantity(sizeModel.askSize(inventory));

        boolean bidOk = bidQty > 0 && spec.passesNotional(bidPx, bidQty) && bidPx < bbo.ask().price();
        boolean askOk = askQty > 0 && spec.passesNotional(askPx, askQty) && askPx > bbo.bid().price();

        return new TargetQuoteSet(key,
                bidOk ? bidPx : Double.NaN,
                bidOk ? bidQty : 0,
                askOk ? askPx : Double.NaN,
                askOk ? askQty : 0,
                nowNs);
    }

    private TargetQuoteSet emptyTarget(MarketKey key, long nowNs) {
        return new TargetQuoteSet(key, Double.NaN, 0, Double.NaN, 0, nowNs);
    }
}
