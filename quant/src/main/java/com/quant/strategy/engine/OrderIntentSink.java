package com.quant.strategy.engine;

import com.quant.common.event.OrderIntent;

/**
 * 下游（通常是风控/OMS 的输入 ring buffer 或 Aeron Publication）的最小写入契约。
 * 实现必须对 Aeron offer backpressure / unavailable 做显式处理，参见设计文档第五节。
 */
@FunctionalInterface
public interface OrderIntentSink {
    void accept(OrderIntent intent);
}
