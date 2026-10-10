package com.quant.common.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.charset.StandardCharsets;

/**
 * ASCII 短字符串的 length-prefixed 编解码助手。
 * 用于 symbol / exchange / clientOrderId 等稳定字段。
 */
public final class StringCodecUtil {

    private StringCodecUtil() {}

    public static int encode(String s, MutableDirectBuffer buffer, int offset) {
        if (s == null) {
            buffer.putShort(offset, (short) -1);
            return Short.BYTES;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("string too long: " + bytes.length);
        }
        buffer.putShort(offset, (short) bytes.length);
        buffer.putBytes(offset + Short.BYTES, bytes);
        return Short.BYTES + bytes.length;
    }

    public static String decode(DirectBuffer buffer, int offset, int[] bytesReadOut) {
        short len = buffer.getShort(offset);
        if (len < 0) {
            bytesReadOut[0] = Short.BYTES;
            return null;
        }
        byte[] bytes = new byte[len];
        buffer.getBytes(offset + Short.BYTES, bytes);
        bytesReadOut[0] = Short.BYTES + len;
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
