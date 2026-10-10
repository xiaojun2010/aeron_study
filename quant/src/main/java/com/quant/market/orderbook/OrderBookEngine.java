package com.quant.market.orderbook;

import com.quant.common.model.Bbo;
import com.quant.common.model.MarketKey;
import com.quant.common.model.PriceLevel;
import com.quant.common.model.Side;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 基于红黑树的本地 L2 订单簿。
 *
 * 设计要点（参照设计文档第四、八节）：
 *  1. 单写者 —— OrderBookEngine 的所有变更只能来自绑定该 shard 的单个线程。
 *  2. 序列号缺口必须被检测——isLive() 在首次 snapshot 到来前为 false；
 *     检测到 seq 跳变后进入 stale 状态并等待 recovery。
 *  3. 不负责下单决策；仅负责维护 book 与 BBO 快照。
 */
public final class OrderBookEngine {

    private final MarketKey key;
    private final NavigableMap<Double, Double> bids = new TreeMap<>((a, b) -> Double.compare(b, a));
    private final NavigableMap<Double, Double> asks = new TreeMap<>();

    private long lastSequence = -1L;
    private boolean live;
    private boolean stale;
    private long updateTimeNs;

    public OrderBookEngine(MarketKey key) {
        this.key = key;
    }

    public MarketKey key() {
        return key;
    }

    public boolean isLive() {
        return live && !stale && !bids.isEmpty() && !asks.isEmpty();
    }

    public boolean isStale() {
        return stale;
    }

    public void applySnapshot(long sequence, double[] bidPrices, double[] bidSizes,
                              double[] askPrices, double[] askSizes, long nowNs) {
        bids.clear();
        asks.clear();
        for (int i = 0; i < bidPrices.length; i++) {
            if (bidSizes[i] > 0) bids.put(bidPrices[i], bidSizes[i]);
        }
        for (int i = 0; i < askPrices.length; i++) {
            if (askSizes[i] > 0) asks.put(askPrices[i], askSizes[i]);
        }
        this.lastSequence = sequence;
        this.updateTimeNs = nowNs;
        this.live = true;
        this.stale = false;
    }

    /**
     * L2 增量更新。size==0 表示删除；sequence 必须严格递增 1，否则标记 stale。
     */
    public void applyDelta(long sequence, Side side, double price, double size, long nowNs) {
        if (!live) {
            // 增量到达但没有 snapshot，直接丢弃并等待快照——避免产生不一致 book
            return;
        }
        if (lastSequence >= 0 && sequence != lastSequence + 1) {
            this.stale = true;
            return;
        }
        NavigableMap<Double, Double> book = side == Side.BUY ? bids : asks;
        if (size <= 0) {
            book.remove(price);
        } else {
            book.put(price, size);
        }
        this.lastSequence = sequence;
        this.updateTimeNs = nowNs;
    }

    public Bbo bbo() {
        PriceLevel bid = bids.isEmpty() ? PriceLevel.EMPTY
                : new PriceLevel(bids.firstKey(), bids.firstEntry().getValue());
        PriceLevel ask = asks.isEmpty() ? PriceLevel.EMPTY
                : new PriceLevel(asks.firstKey(), asks.firstEntry().getValue());
        return new Bbo(key, bid, ask, lastSequence, updateTimeNs);
    }

    public long lastSequence() {
        return lastSequence;
    }

    public void markRecoveryRequired() {
        this.stale = true;
    }

    public void reset() {
        bids.clear();
        asks.clear();
        live = false;
        stale = false;
        lastSequence = -1L;
    }
}
