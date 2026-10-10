package com.quant.strategy.reconciler;

/**
 * 幂等 id 工厂。禁止多策略共享同一个实例（会导致 id 冲突）。
 * 单写者线程调用；默认实现使用 strategyId + 单调序列。
 */
public final class ClientOrderIdFactory {

    private final String prefix;
    private long counter;

    public ClientOrderIdFactory(String prefix, long seed) {
        this.prefix = prefix;
        this.counter = seed;
    }

    public String next() {
        return prefix + "-" + Long.toUnsignedString(++counter);
    }
}
