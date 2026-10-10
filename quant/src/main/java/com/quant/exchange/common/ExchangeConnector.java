package com.quant.exchange.common;

import com.quant.common.event.OrderIntent;
import com.quant.common.model.MarketKey;
import com.quant.oms.execution.ExecutionGateway;

import java.util.List;

/**
 * 单一交易所适配器接口。订阅行情 + 发送订单。
 * 行情通过 MarketListener 回调，订单通过 ExecutionGateway。
 */
public interface ExchangeConnector extends ExecutionGateway, AutoCloseable {

    void connect();

    void subscribeMarketData(List<MarketKey> keys, MarketListener listener);

    @Override
    default void send(OrderIntent intent, SendCallback callback) {
        throw new UnsupportedOperationException("adapter must implement send");
    }

    @Override
    void close();
}
