package com.lumina.orderbook.engine;

import java.util.UUID;

/** Input to the engine. Timestamps come from the gateway so processing never reads a clock. */
public sealed interface EngineCommand {

    String symbol();

    long timestampMs();

    record Place(
            String symbol,
            UUID orderId,
            String accountId,
            Side side,
            OrderType type,
            TimeInForce timeInForce,
            boolean postOnly,
            long priceTicks,
            long quantityLots,
            long timestampMs) implements EngineCommand {
    }

    record Cancel(
            String symbol,
            UUID orderId,
            String accountId,
            long timestampMs) implements EngineCommand {
    }
}
