package com.lumina.orderbook.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Resilience4jMetricsConfig {

    public Resilience4jMetricsConfig(CircuitBreakerRegistry circuitBreakerRegistry, MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);
    }
}
