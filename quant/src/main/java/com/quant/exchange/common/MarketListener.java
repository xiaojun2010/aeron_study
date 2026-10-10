package com.quant.exchange.common;

import com.quant.common.model.Bbo;
import com.quant.common.model.Trade;

/**
 * 行情网关归一化后的回调。该回调通常在 Netty I/O 线程上触发——
 * 实现禁止做阻塞操作，应立即将事件推入 Disruptor。
 */
public interface MarketListener {

    void onBbo(Bbo bbo, long receiveTimeNs);

    void onTrade(Trade trade, long receiveTimeNs);
}
