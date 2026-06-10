package com.lumina.orderbook.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Trade {

    private final UUID id;
    private final UUID buyOrderId;
    private final UUID sellOrderId;
    private final String symbol;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final Instant executedAt;

    public Trade(
            UUID id,
            UUID buyOrderId,
            UUID sellOrderId,
            String symbol,
            BigDecimal price,
            BigDecimal quantity,
            Instant executedAt) {
        this.id = id;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.executedAt = executedAt;
    }

    public static Trade create(
            UUID buyOrderId,
            UUID sellOrderId,
            String symbol,
            BigDecimal price,
            BigDecimal quantity) {
        return new Trade(
                UUID.randomUUID(),
                buyOrderId,
                sellOrderId,
                symbol,
                price,
                quantity,
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public UUID getBuyOrderId() {
        return buyOrderId;
    }

    public UUID getSellOrderId() {
        return sellOrderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
