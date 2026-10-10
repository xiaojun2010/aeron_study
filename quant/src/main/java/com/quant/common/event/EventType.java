package com.quant.common.event;

/**
 * 跨进程/跨 RingBuffer 传输的事件类型。
 * 使用 byte 常量而非 enum ordinal 便于 wire codec 版本控制。
 */
public final class EventType {
    public static final byte BBO_UPDATE = 1;
    public static final byte DEPTH_UPDATE = 2;
    public static final byte TRADE = 3;
    public static final byte BOOK_SNAPSHOT = 4;

    public static final byte QUOTE_INTENT = 10;
    public static final byte ORDER_INTENT = 11;
    public static final byte APPROVED_ORDER_INTENT = 12;

    public static final byte EXECUTION_REPORT = 20;
    public static final byte POSITION_UPDATE = 21;

    public static final byte HEALTH_HEARTBEAT = 90;
    public static final byte KILL_SWITCH = 91;

    private EventType() {}
}
