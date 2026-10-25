package com.lumina.orderbook.view;

import com.lumina.orderbook.engine.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeView(
        UUID id,
        String symbol,
        long sequence,
        BigDecimal price,
        BigDecimal quantity,
        Side takerSide,
        UUID buyOrderId,
        UUID sellOrderId,
        Instant executedAt) {
}
