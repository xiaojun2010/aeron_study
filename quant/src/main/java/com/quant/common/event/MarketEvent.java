package com.quant.common.event;

import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.common.model.Trade;

/**
 * Disruptor 槽位事件（mutable 以便复用）。
 * 不同类型的事件共享同一槽位——通过 type 字段分发。
 */
public final class MarketEvent {

    private byte type;
    private MarketKey key;
    private long sequence;
    private long receiveTimeNs;

    private Bbo bbo;
    private Trade trade;

    public byte type() { return type; }
    public MarketKey key() { return key; }
    public long sequence() { return sequence; }
    public long receiveTimeNs() { return receiveTimeNs; }
    public Bbo bbo() { return bbo; }
    public Trade trade() { return trade; }

    public MarketEvent asBbo(Bbo bbo, long receiveTimeNs) {
        this.type = EventType.BBO_UPDATE;
        this.key = bbo.key();
        this.sequence = bbo.sequence();
        this.receiveTimeNs = receiveTimeNs;
        this.bbo = bbo;
        this.trade = null;
        return this;
    }

    public MarketEvent asTrade(Trade trade, long receiveTimeNs) {
        this.type = EventType.TRADE;
        this.key = trade.key();
        this.sequence = trade.sequence();
        this.receiveTimeNs = receiveTimeNs;
        this.bbo = null;
        this.trade = trade;
        return this;
    }

    public void recycle() {
        this.type = 0;
        this.key = null;
        this.bbo = null;
        this.trade = null;
    }
}
