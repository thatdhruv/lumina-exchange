package com.lumina.orderbook.view;

import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.RejectReason;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An order as clients see it. Side, type, price and quantity are null only for orders the engine
 * rejected before accepting them (the event carries just id, account and reason).
 */
public record OrderView(
        UUID id,
        String accountId,
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
}
