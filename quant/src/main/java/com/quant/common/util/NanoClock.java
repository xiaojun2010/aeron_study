package com.quant.common.util;

/**
 * 单一时间源抽象，便于在回放/回测中注入虚拟时钟。
 * 热路径一律通过该接口获取 ns 戳。
 */
public interface NanoClock {

    long nanoTime();

    NanoClock SYSTEM = System::nanoTime;
}
