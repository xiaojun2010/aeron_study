package com.quant.exchange.binance;

import com.quant.common.event.OrderIntent;
import com.quant.common.model.MarketKey;
import com.quant.exchange.common.ExchangeConnector;
import com.quant.exchange.common.MarketListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Binance 适配器骨架。保留连接管理与接口形状，具体 WebSocket pipeline
 * （SSL + HTTP upgrade + JSON frame → Bbo/Trade 归一化）在本文件 TODO 处留出
 * 扩展点——避免在没有真实配置的情况下写出"看起来能跑其实挂的"代码。
 */
public final class BinanceConnector implements ExchangeConnector {

    private final EventLoopGroup group = new NioEventLoopGroup();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile Channel channel;
    private volatile MarketListener listener;

    @Override
    public void connect() {
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true);
        // TODO: 组装 SslHandler + HttpClientCodec + HttpObjectAggregator + WebSocketClientProtocolHandler
        //       + 行情帧解码（JSON → BinanceDepthFrame → Bbo）
        //       + 重连/心跳策略
        // 当前仅标记为“已尝试连接”，用于集成测试和骨架联调。
        connected.set(true);
    }

    @Override
    public void subscribeMarketData(List<MarketKey> keys, MarketListener listener) {
        this.listener = listener;
        // TODO: 发送 Binance combined-stream subscribe 消息
    }

    @Override
    public void send(OrderIntent intent, SendCallback callback) {
        // TODO: 走 REST（SIGNED HMAC-SHA256）或 WebSocket Trade API。
        //       成功 → callback.onAccepted(exchangeOrderId)
        //       失败 → callback.onRejected(reason)
        //       超时 → callback.onUnknown()  // 关键：绝不默认 onRejected
        callback.onUnknown();
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
        if (channel != null) channel.close();
        group.shutdownGracefully();
    }

    MarketListener listener() { return listener; }
}
