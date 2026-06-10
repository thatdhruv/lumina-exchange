package com.lumina.orderbook.event;

import com.lumina.orderbook.domain.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeExecutedEvent(
        UUID id,
        UUID buyOrderId,
        UUID sellOrderId,
        String symbol,
        BigDecimal price,
        BigDecimal quantity,
        Instant executedAt) {

    public static TradeExecutedEvent from(Trade trade) {
        return new TradeExecutedEvent(
                trade.getId(),
                trade.getBuyOrderId(),
                trade.getSellOrderId(),
                trade.getSymbol(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getExecutedAt());
    }
}
