package com.lumina.orderbook.api.dto;

import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull OrderType type,
        BigDecimal price,
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "quantity must be positive")
        BigDecimal quantity) {
}
