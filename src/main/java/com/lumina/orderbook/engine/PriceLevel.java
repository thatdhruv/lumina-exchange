package com.lumina.orderbook.engine;

import java.math.BigDecimal;

public record PriceLevel(BigDecimal price, BigDecimal quantity) {
}
