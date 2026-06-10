package com.lumina.orderbook.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Order {

    private final UUID id;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal price;
    private BigDecimal quantity;
    private OrderStatus status;
    private final Instant createdAt;

    public Order(
            UUID id,
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal price,
            BigDecimal quantity,
            OrderStatus status,
            Instant createdAt) {
        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Order newOpen(
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal price,
            BigDecimal quantity) {
        return new Order(
                UUID.randomUUID(),
                symbol,
                side,
                type,
                price,
                quantity,
                OrderStatus.OPEN,
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIAL;
    }
}
