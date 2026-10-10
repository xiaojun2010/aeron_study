package com.quant.common.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 跨 Aeron Publication 的最小消息编码契约。
 * 真实系统应切换到 SBE 等 schema 化编码；此接口仅规范边界。
 */
public interface MessageCodec<T> {

    byte eventType();

    int encode(T message, MutableDirectBuffer buffer, int offset);

    T decode(DirectBuffer buffer, int offset, int length);
}
