package com.lumina.orderbook.gateway;

import com.lumina.orderbook.engine.RejectReason;
import com.lumina.orderbook.view.OrderView;

import java.util.Optional;
import java.util.UUID;

public sealed interface PlaceOutcome {

    /** The engine accepted the order; the view reflects any immediate fills. */
    record Placed(OrderView order) implements PlaceOutcome {
    }

    /** Same idempotency key seen before. {@code existing} is empty if the read model has not caught up yet. */
    record Duplicate(UUID orderId, Optional<OrderView> existing) implements PlaceOutcome {
    }

    record Rejected(UUID orderId, RejectReason reason) implements PlaceOutcome {
    }
}
