package com.lumina.orderbook.api.dto;

import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotBlank String symbol,
        @NotNull Side side,
        @NotNull OrderType type,
        TimeInForce timeInForce,
        Boolean postOnly,
        @Positive BigDecimal price,
        @NotNull @Positive BigDecimal quantity) {
}
