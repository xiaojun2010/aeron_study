package com.quant.risk.killswitch;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局 Kill Switch。设计文档第六节：风控/OMS 必须订阅并立即停止新单。
 * 使用 AtomicReference 以附带原因，便于审计。
 */
public final class KillSwitch {

    public record Reason(String code, String message, long triggeredAtNs) {}

    private final AtomicReference<Reason> state = new AtomicReference<>();

    public boolean isEngaged() {
        return state.get() != null;
    }

    public Reason reason() {
        return state.get();
    }

    public boolean engage(String code, String message, long nowNs) {
        return state.compareAndSet(null, new Reason(code, message, nowNs));
    }

    public void release() {
        state.set(null);
    }
}
