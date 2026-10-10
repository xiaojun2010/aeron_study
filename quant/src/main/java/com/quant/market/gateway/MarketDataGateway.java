package com.quant.market.gateway;

import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.common.model.Trade;
import com.quant.common.util.NanoClock;
import com.quant.exchange.common.ExchangeConnector;
import com.quant.exchange.common.MarketListener;
import com.quant.strategy.disruptor.ShardRouter;

import java.util.List;

/**
 * 行情网关：订阅交易所 → 归一化 → 路由到 shard Disruptor。
 * 本身没有状态——所有本地订单簿都在 shard 内。
 */
public final class MarketDataGateway implements MarketListener {

    private final ExchangeConnector connector;
    private final ShardRouter router;
    private final NanoClock clock;

    public MarketDataGateway(ExchangeConnector connector, ShardRouter router, NanoClock clock) {
        this.connector = connector;
        this.router = router;
        this.clock = clock;
    }

    public void start(List<MarketKey> subscriptions) {
        connector.connect();
        connector.subscribeMarketData(subscriptions, this);
    }

    @Override
    public void onBbo(Bbo bbo, long receiveTimeNs) {
        router.publishBbo(bbo, receiveTimeNs);
    }

    @Override
    public void onTrade(Trade trade, long receiveTimeNs) {
        router.publishTrade(trade, receiveTimeNs);
    }

    public NanoClock clock() {
        return clock;
    }

    public void stop() {
        connector.close();
    }
}
