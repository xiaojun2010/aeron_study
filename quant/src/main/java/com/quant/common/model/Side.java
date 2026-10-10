package com.quant.common.model;

public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    public int sign() {
        return this == BUY ? 1 : -1;
    }
}
