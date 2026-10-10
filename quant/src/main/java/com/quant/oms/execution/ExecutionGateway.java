package com.quant.oms.execution;

import com.quant.common.event.OrderIntent;

/**
 * OMS → 交易所执行通道的最小接口。
 * 实现负责：序列化、网络 I/O、失败分类、ACK 回传。
 */
public interface ExecutionGateway {

    void send(OrderIntent intent, SendCallback callback);

    boolean isConnected();

    interface SendCallback {
        void onAccepted(String exchangeOrderId);
        void onRejected(String reason);
        void onUnknown();
    }
}
