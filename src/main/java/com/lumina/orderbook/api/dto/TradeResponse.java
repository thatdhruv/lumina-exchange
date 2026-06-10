package com.lumina.orderbook.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TradeResponse(
        UUID id,
        UUID buyOrderId,
        UUID sellOrderId,
        String symbol,
        BigDecimal price,
        BigDecimal quantity,
        Instant executedAt) {

    public static TradeResponse fromRedisHash(Map<Object, Object> hash) {
        return new TradeResponse(
                UUID.fromString(String.valueOf(hash.get("id"))),
                UUID.fromString(String.valueOf(hash.get("buyOrderId"))),
                UUID.fromString(String.valueOf(hash.get("sellOrderId"))),
                String.valueOf(hash.get("symbol")),
                new BigDecimal(String.valueOf(hash.get("price"))),
                new BigDecimal(String.valueOf(hash.get("quantity"))),
                Instant.parse(String.valueOf(hash.get("executedAt"))));
    }
}
