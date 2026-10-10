package com.quant.bootstrap;

import com.lmax.disruptor.dsl.Disruptor;
import com.quant.common.event.MarketEvent;
import com.quant.common.event.OrderAction;
import com.quant.common.model.MarketKey;
import com.quant.common.util.NanoClock;
import com.quant.market.gateway.SimulatedMarketDataSource;
import com.quant.risk.killswitch.KillSwitch;
import com.quant.risk.limits.AccountLimits;
import com.quant.risk.limits.PriceDeviationCheck;
import com.quant.risk.pretrade.PreTradeRiskEngine;
import com.quant.risk.pretrade.RiskDecision;
import com.quant.strategy.disruptor.MarketShardHandler;
import com.quant.strategy.disruptor.ShardRouter;
import com.quant.strategy.disruptor.StrategyDisruptorFactory;
import com.quant.strategy.engine.OrderIntentSink;
import com.quant.strategy.engine.StrategyEngine;
import com.quant.strategy.fairprice.MicropriceCalculator;
import com.quant.strategy.inventory.ReservationPriceModel;
import com.quant.strategy.quoting.InstrumentSpec;
import com.quant.strategy.quoting.QuoteEngine;
import com.quant.strategy.quoting.QuoteSizeModel;
import com.quant.strategy.quoting.SpreadModel;
import com.quant.strategy.quoting.VolatilityModel;
import com.quant.strategy.reconciler.ClientOrderIdFactory;
import com.quant.strategy.reconciler.QuoteReconciler;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把整条流水线拉起来的入口（行情模拟 → shard Disruptor → 策略 → QuoteReconciler → 风控）。
 * 真实部署应拆成多个进程（Gateway / Strategy / Risk+OMS / Execution），
 * 通过 Aeron IPC 连接；此 Main 用于本地冒烟。
 */
public final class BootstrapMain {

    public static void main(String[] args) throws Exception {
        List<MarketKey> universe = List.of(
                MarketKey.of("BINANCE", "BTCUSDT"),
                MarketKey.of("BINANCE", "ETHUSDT"),
                MarketKey.of("BINANCE", "SOLUSDT"),
                MarketKey.of("BINANCE", "XRPUSDT")
        );

        int shardCount = 2;
        MarketShardHandler[] handlers = new MarketShardHandler[shardCount];
        for (int i = 0; i < shardCount; i++) handlers[i] = new MarketShardHandler(i);

        Disruptor<MarketEvent>[] disruptors =
                new StrategyDisruptorFactory(shardCount, 1 << 14, false).build(handlers);
        for (Disruptor<MarketEvent> d : disruptors) d.start();

        ShardRouter router = new ShardRouter(disruptors);

        KillSwitch killSwitch = new KillSwitch();
        AccountLimits limits = new AccountLimits(1_000_000, 100_000, 1_000);
        PriceDeviationCheck priceCheck = new PriceDeviationCheck(500); // 5%
        PreTradeRiskEngine risk = new PreTradeRiskEngine(killSwitch, limits, priceCheck);

        AtomicLong placed = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        OrderIntentSink sink = intent -> {
            risk.updateBbo(intent.action() == OrderAction.CANCEL
                    ? null
                    : null); // 由主流程喂 BBO；此处略
            RiskDecision d = risk.check(intent);
            if (d.approved()) placed.incrementAndGet();
            else rejected.incrementAndGet();
        };

        long strategyIdSeed = 1_000;
        InstrumentSpec spec = new InstrumentSpec(0.01, 0.001, 10.0, 1000);

        for (MarketKey key : universe) {
            MarketShardHandler shard = handlers[router.shardFor(key)];
            QuoteEngine engine = new QuoteEngine(
                    new MicropriceCalculator(),
                    new ReservationPriceModel(0.01, 1.0),
                    new SpreadModel(5, 0.5, 0.02),
                    new QuoteSizeModel(1.0, 0.1, 20.0),
                    new VolatilityModel(0.2),
                    spec);
            QuoteReconciler reconciler = new QuoteReconciler(
                    spec.tickSize() / 2.0, spec.stepSize() / 2.0,
                    new ClientOrderIdFactory("S" + strategyIdSeed + "-" + key.symbol(), 0),
                    false);
            StrategyEngine strategy = new StrategyEngine(
                    key, engine, reconciler,
                    NanoClock.SYSTEM::nanoTime, strategyIdSeed++, sink);
            shard.register(strategy);
            risk.updateBbo(new com.quant.common.model.Bbo(key,
                    new com.quant.common.model.PriceLevel(99.95, 1),
                    new com.quant.common.model.PriceLevel(100.05, 1),
                    0, System.nanoTime()));
        }

        SimulatedMarketDataSource source = new SimulatedMarketDataSource(
                universe, NanoClock.SYSTEM, 1_000);
        source.start(new com.quant.exchange.common.MarketListener() {
            @Override public void onBbo(com.quant.common.model.Bbo bbo, long ns) {
                risk.updateBbo(bbo);
                router.publishBbo(bbo, ns);
            }
            @Override public void onTrade(com.quant.common.model.Trade trade, long ns) {
                router.publishTrade(trade, ns);
            }
        });

        System.out.println("quant pipeline running. ctrl-c to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            source.stop();
            router.shutdown();
            System.out.printf("stopped. placed=%d rejected=%d%n",
                    placed.get(), rejected.get());
        }));

        TimeUnit.SECONDS.sleep(Long.parseLong(System.getProperty("quant.runSeconds", "5")));
        source.stop();
        router.shutdown();
        System.out.printf("done. placed=%d rejected=%d%n", placed.get(), rejected.get());
    }
}
