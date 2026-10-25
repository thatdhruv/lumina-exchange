package com.lumina.orderbook.view;

import java.math.BigDecimal;
import java.util.List;

/** Aggregated depth. {@code sequence} is the last event applied for the symbol. */
public record BookView(String symbol, long sequence, List<Level> bids, List<Level> asks) {

    public record Level(BigDecimal price, BigDecimal quantity) {
    }
}
