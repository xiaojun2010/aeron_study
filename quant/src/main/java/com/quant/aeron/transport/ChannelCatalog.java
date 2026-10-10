package com.quant.aeron.transport;

/**
 * Aeron Stream ID 分配表。对应设计文档第五节表格。
 * Stream ID 只是逻辑分片，真实部署还需 session / channel 规划。
 */
public final class ChannelCatalog {

    public static final String IPC_CHANNEL = "aeron:ipc";

    public static final int STREAM_NORMALIZED_MARKET = 1001;
    public static final int STREAM_BOOK_SNAPSHOT = 1002;

    public static final int STREAM_QUOTE_INTENT = 2001;
    public static final int STREAM_APPROVED_ORDER_INTENT = 2002;

    public static final int STREAM_EXECUTION_REPORT = 3001;
    public static final int STREAM_POSITION_STATE = 3002;

    public static final int STREAM_CONTROL = 9001;

    private ChannelCatalog() {}
}
