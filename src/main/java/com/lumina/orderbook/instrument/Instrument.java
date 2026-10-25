package com.lumina.orderbook.instrument;

import com.lumina.orderbook.gateway.GatewayException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Trading rules for one symbol. The engine only sees integers: prices in ticks, quantities in
 * lots. Everything user-facing converts at the edge through this class.
 */
public record Instrument(
        String symbol,
        BigDecimal tickSize,
        BigDecimal lotSize,
        BigDecimal minQuantity,
        BigDecimal maxQuantity) {

    public long toTicks(BigDecimal price) {
        if (price.signum() <= 0) {
            throw GatewayException.invalid("INVALID_PRICE", "price must be positive");
        }
        return exactMultiple(price, tickSize, "INVALID_PRICE", "price must be a multiple of the tick size " + tickSize.toPlainString());
    }

    public long toLots(BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            throw GatewayException.invalid("INVALID_QUANTITY", "quantity must be positive");
        }
        if (quantity.compareTo(minQuantity) < 0) {
            throw GatewayException.invalid("INVALID_QUANTITY", "quantity is below the minimum " + minQuantity.toPlainString());
        }
        if (quantity.compareTo(maxQuantity) > 0) {
            throw GatewayException.invalid("INVALID_QUANTITY", "quantity is above the maximum " + maxQuantity.toPlainString());
        }
        return exactMultiple(quantity, lotSize, "INVALID_QUANTITY", "quantity must be a multiple of the lot size " + lotSize.toPlainString());
    }

    public BigDecimal price(long ticks) {
        return tickSize.multiply(BigDecimal.valueOf(ticks));
    }

    public BigDecimal quantity(long lots) {
        return lotSize.multiply(BigDecimal.valueOf(lots));
    }

    private static long exactMultiple(BigDecimal value, BigDecimal unit, String code, String message) {
        try {
            return value.divide(unit, 0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw GatewayException.invalid(code, message);
        }
    }
}
