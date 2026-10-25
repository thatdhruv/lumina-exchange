package com.lumina.orderbook.engine;

import java.util.List;
import java.util.UUID;

/** Output of the engine. {@code seq} is strictly increasing and gap-free per symbol. */
public sealed interface EngineEvent {

    long seq();

    long timestampMs();

    record Accepted(
            long seq,
            long timestampMs,
            UUID orderId,
            String accountId,
            Side side,
            OrderType type,
            TimeInForce timeInForce,
            boolean postOnly,
            long priceTicks,
            long quantityLots) implements EngineEvent {
    }

    record Rejected(long seq, long timestampMs, UUID orderId, String accountId, RejectReason reason)
            implements EngineEvent {
    }

    record Trade(
            long seq,
            long timestampMs,
            UUID tradeId,
            UUID takerOrderId,
            UUID makerOrderId,
            UUID buyOrderId,
            UUID sellOrderId,
            Side takerSide,
            long priceTicks,
            long quantityLots) implements EngineEvent {
    }

    record Cancelled(
            long seq,
            long timestampMs,
            UUID orderId,
            long remainingLots,
            CancelReason reason) implements EngineEvent {
    }

    /** Absolute resting quantity for every level touched by one command. */
    record BookChanged(long seq, long timestampMs, List<LevelChange> levels) implements EngineEvent {
    }

    record LevelChange(Side side, long priceTicks, long totalLots) {
    }
}
