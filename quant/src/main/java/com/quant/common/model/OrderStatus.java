package com.quant.common.model;

/**
 * OMS 订单状态机节点。参见设计文档第六节。
 * UNKNOWN 必须经过对账过程还原，不能直接重发。
 */
public enum OrderStatus {
    NEW_INTENT,
    RISK_APPROVED,
    PENDING_NEW,
    WORKING,
    PARTIALLY_FILLED,
    FILLED,
    PENDING_CANCEL,
    CANCELED,
    REJECTED,
    UNKNOWN
}
