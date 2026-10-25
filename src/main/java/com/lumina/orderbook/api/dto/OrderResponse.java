package com.lumina.orderbook.api.dto;

import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.RejectReason;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;
import com.lumina.orderbook.view.OrderStatus;
import com.lumina.orderbook.view.OrderView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String symbol,
        Side side,
        OrderType type,
        TimeInForce timeInForce,
        boolean postOnly,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        OrderStatus status,
        RejectReason rejectReason,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(OrderView o) {
        return new OrderResponse(o.id(), o.symbol(), o.side(), o.type(), o.timeInForce(), o.postOnly(), o.price(),
                o.quantity(), o.filledQuantity(), o.remainingQuantity(), o.status(), o.rejectReason(),
                o.createdAt(), o.updatedAt());
    }
}
