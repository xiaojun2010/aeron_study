package com.quant.common.model;

public record Trade(MarketKey key,
                    double price,
                    double quantity,
                    Side aggressorSide,
                    long tradeTimeNs,
                    long sequence) {
}
