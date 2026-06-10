package com.lumina.orderbook.metrics;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.Trade;
import com.lumina.orderbook.engine.MatchingEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExchangeMetrics {

    private final MeterRegistry registry;
    private final MatchingEngine matchingEngine;
    private final Set<String> trackedSymbols = ConcurrentHashMap.newKeySet();

    public ExchangeMetrics(MeterRegistry registry, MatchingEngine matchingEngine) {
        this.registry = registry;
        this.matchingEngine = matchingEngine;
    }

    public void recordOrderPlaced(Order order) {
        registry.counter(
                "orders.placed.total",
                "symbol", order.getSymbol(),
                "side", order.getSide().name())
                .increment();
        registerDepthGauge(order.getSymbol());
    }

    public void recordOrderCancelled(Order order) {
        registry.counter("orders.cancelled.total", "symbol", order.getSymbol()).increment();
        registerDepthGauge(order.getSymbol());
    }

    public void recordTradesExecuted(List<Trade> trades) {
        for (Trade trade : trades) {
            registry.counter("trades.executed.total", "symbol", trade.getSymbol()).increment();
            registerDepthGauge(trade.getSymbol());
        }
    }

    public Timer.Sample startMatchingTimer() {
        return Timer.start(registry);
    }

    public void recordMatchingLatency(Timer.Sample sample, String symbol) {
        sample.stop(Timer.builder("matching.latency.ms")
                .description("Time from order receipt to match or no-match")
                .tag("symbol", symbol)
                .publishPercentileHistogram()
                .register(registry));
    }

    private void registerDepthGauge(String symbol) {
        if (trackedSymbols.add(symbol)) {
            Gauge.builder("orderbook.depth", matchingEngine, engine -> engine.getOpenOrderCount(symbol))
                    .description("Total open orders on the book for a symbol")
                    .tag("symbol", symbol)
                    .register(registry);
        }
    }
}
