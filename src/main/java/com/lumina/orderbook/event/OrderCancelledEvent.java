package com.lumina.orderbook.event;

import com.lumina.orderbook.domain.Order;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelledEvent(
        UUID id,
        String symbol,
        Instant cancelledAt) {

    public static OrderCancelledEvent from(Order order) {
        return new OrderCancelledEvent(order.getId(), order.getSymbol(), Instant.now());
    }
}
