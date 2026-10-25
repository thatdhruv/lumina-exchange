package com.lumina.orderbook.engine;

import java.util.List;
import java.util.UUID;

/** Serializable image of one {@link OrderBook}. */
public record BookState(
        String symbol,
        long lastSeq,
        long nextArrival,
        List<Resting> orders,
        List<UUID> recentOrderIds) {

    public record Resting(
            UUID orderId,
            String accountId,
            Side side,
            long priceTicks,
            long remainingLots,
            long arrival) {
    }
}
