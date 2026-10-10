package com.quant.common.model;

import java.util.Objects;

/**
 * 行情/订单的最小寻址键：交易所 + 交易对。
 * 作为 Disruptor 分片 key、OrderBook 索引 key、OMS 子账本 key 的统一标识。
 */
public record MarketKey(String exchange, String symbol) {

    public MarketKey {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(symbol, "symbol");
    }

    public static MarketKey of(String exchange, String symbol) {
        return new MarketKey(exchange, symbol);
    }

    @Override
    public String toString() {
        return exchange + ":" + symbol;
    }
}
