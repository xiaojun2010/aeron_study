package com.quant.aeron.subscription;

import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.concurrent.IdleStrategy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单线程 Aeron Subscription 轮询循环。
 * FragmentAssembler 用于处理超过 MTU 被拆分的消息。
 */
public final class PollingSubscriber implements AutoCloseable, Runnable {

    private final Subscription subscription;
    private final FragmentAssembler assembler;
    private final IdleStrategy idleStrategy;
    private final int fragmentLimit;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public PollingSubscriber(Subscription subscription,
                             FragmentHandler handler,
                             IdleStrategy idleStrategy,
                             int fragmentLimit) {
        this.subscription = subscription;
        this.assembler = new FragmentAssembler(handler);
        this.idleStrategy = idleStrategy;
        this.fragmentLimit = fragmentLimit;
    }

    @Override
    public void run() {
        while (running.get()) {
            int polled = subscription.poll(assembler, fragmentLimit);
            idleStrategy.idle(polled);
        }
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void close() {
        stop();
        CloseHelper.quietClose(subscription);
    }
}
