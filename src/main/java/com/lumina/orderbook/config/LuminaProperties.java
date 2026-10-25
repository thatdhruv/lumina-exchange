package com.lumina.orderbook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "lumina")
public record LuminaProperties(
        @DefaultValue({"api", "engine", "projector"}) List<String> roles,
        @DefaultValue Kafka kafka,
        @DefaultValue Engine engine,
        @DefaultValue Gateway gateway,
        @DefaultValue Security security,
        @DefaultValue Cors cors,
        @DefaultValue List<InstrumentConfig> instruments) {

    public record Kafka(
            @DefaultValue("exchange.commands") String commandsTopic,
            @DefaultValue("exchange.events") String eventsTopic,
            /** Fixed for the life of the cluster: symbol-to-partition ownership depends on it. */
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicationFactor,
            @DefaultValue("1") int minInsyncReplicas) {
    }

    public record Engine(
            @DefaultValue("10000") int snapshotEveryCommands,
            @DefaultValue("30s") Duration snapshotMaxInterval,
            @DefaultValue("50000") int recentOrderIdCapacity) {
    }

    public record Gateway(
            @DefaultValue("5s") Duration commandTimeout,
            @DefaultValue("50") double rateLimitPerSecond,
            @DefaultValue("100") int rateLimitBurst,
            @DefaultValue("5s") Duration bookResendInterval) {
    }

    public record Security(@DefaultValue List<ApiKey> apiKeys) {
    }

    public record ApiKey(String key, String account) {
    }

    public record Cors(@DefaultValue("http://localhost:3000") List<String> allowedOrigins) {
    }

    public record InstrumentConfig(
            String symbol,
            BigDecimal tickSize,
            BigDecimal lotSize,
            BigDecimal minQuantity,
            BigDecimal maxQuantity) {
    }
}
