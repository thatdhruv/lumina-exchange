package com.lumina.orderbook.engine;

import java.util.List;

public record OrderBookSnapshot(List<PriceLevel> bids, List<PriceLevel> asks) {
}
