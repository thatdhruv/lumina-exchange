package com.lumina.orderbook.gateway;

import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;

import java.math.BigDecimal;

public record PlaceRequest(
        String symbol,
        Side side,
        OrderType type,
        TimeInForce timeInForce,
        boolean postOnly,
        BigDecimal price,
        BigDecimal quantity) {
}
