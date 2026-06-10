package com.lumina.orderbook.api.dto;

import com.lumina.orderbook.engine.OrderBookSnapshot;
import com.lumina.orderbook.engine.PriceLevel;

import java.math.BigDecimal;
import java.util.List;

public record OrderBookResponse(String symbol, List<Level> bids, List<Level> asks) {

    public record Level(BigDecimal price, BigDecimal quantity) {
        public static Level from(PriceLevel level) {
            return new Level(level.price(), level.quantity());
        }
    }

    public static OrderBookResponse from(String symbol, OrderBookSnapshot snapshot) {
        return new OrderBookResponse(
                symbol,
                snapshot.bids().stream().map(Level::from).toList(),
                snapshot.asks().stream().map(Level::from).toList());
    }
}
