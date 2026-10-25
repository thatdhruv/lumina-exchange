package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.LuminaProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final RateLimiter limiter = new RateLimiter(new LuminaProperties(
            List.of("api"),
            new LuminaProperties.Kafka("c", "e", 1, (short) 1, 1),
            new LuminaProperties.Engine(1, Duration.ofSeconds(1), 1),
            new LuminaProperties.Gateway(Duration.ofSeconds(1), 2.0, 3, Duration.ofSeconds(1)),
            new LuminaProperties.Security(List.of()),
            new LuminaProperties.Cors(List.of()),
            List.of()));

    @Test
    void allowsABurstThenThrottles() {
        assertThat(limiter.tryAcquire("a", 0)).isTrue();
        assertThat(limiter.tryAcquire("a", 0)).isTrue();
        assertThat(limiter.tryAcquire("a", 0)).isTrue();
        assertThat(limiter.tryAcquire("a", 0)).isFalse();
    }

    @Test
    void refillsAtTheConfiguredRate() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a", 0);
        }
        assertThat(limiter.tryAcquire("a", SECOND / 4)).isFalse();
        assertThat(limiter.tryAcquire("a", SECOND / 2)).isTrue();
        assertThat(limiter.tryAcquire("a", SECOND / 2)).isFalse();
    }

    @Test
    void accountsAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a", 0);
        }
        assertThat(limiter.tryAcquire("b", 0)).isTrue();
    }
}
