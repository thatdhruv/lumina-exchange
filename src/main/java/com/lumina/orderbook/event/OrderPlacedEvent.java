package com.lumina.orderbook.event;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderPlacedEvent(
        UUID id,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        BigDecimal quantity,
        OrderStatus status,
        Instant createdAt) {

    public static OrderPlacedEvent from(Order order) {
        return new OrderPlacedEvent(
                order.getId(),
                order.getSymbol(),
                order.getSide(),
                order.getType(),
                order.getPrice(),
                order.getQuantity(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
