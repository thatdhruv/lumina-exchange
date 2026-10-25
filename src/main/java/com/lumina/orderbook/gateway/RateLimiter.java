package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.LuminaProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-account token bucket. Limits are per gateway instance; enforce a global limit at the edge
 * (ingress or API gateway) if you need one.
 */
@Component
public class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final double perSecond;
    private final int burst;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(LuminaProperties props) {
        this.perSecond = props.gateway().rateLimitPerSecond();
        this.burst = props.gateway().rateLimitBurst();
    }

    public void check(String account) {
        if (!tryAcquire(account, System.nanoTime())) {
            throw GatewayException.rateLimited();
        }
    }

    boolean tryAcquire(String account, long nowNanos) {
        Bucket bucket = buckets.computeIfAbsent(account, a -> new Bucket(burst, nowNanos));
        synchronized (bucket) {
            double elapsedSeconds = (nowNanos - bucket.lastRefillNanos) / 1e9;
            bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * perSecond);
            bucket.lastRefillNanos = nowNanos;
            if (bucket.tokens < 1) {
                return false;
            }
            bucket.tokens -= 1;
            return true;
        }
    }
}
