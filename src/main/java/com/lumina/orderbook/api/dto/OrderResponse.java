package com.lumina.orderbook.api.dto;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        BigDecimal quantity,
        OrderStatus status,
        Instant createdAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
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
