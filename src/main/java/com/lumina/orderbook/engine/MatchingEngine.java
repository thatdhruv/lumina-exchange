package com.lumina.orderbook.engine;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.Trade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class MatchingEngine {

    private static final int DEFAULT_DEPTH = 10;

    private final ConcurrentMap<String, SymbolOrderBook> books = new ConcurrentHashMap<>();

    public MatchResult placeOrder(Order order) {
        SymbolOrderBook book = books.computeIfAbsent(order.getSymbol(), symbol -> new SymbolOrderBook());
        List<Trade> trades = new ArrayList<>(book.placeOrder(order));
        return new MatchResult(order, trades);
    }

    public boolean cancelOrder(String symbol, UUID orderId) {
        SymbolOrderBook book = books.get(symbol);
        if (book == null) {
            return false;
        }
        return book.cancelOrder(orderId);
    }

    public OrderBookSnapshot getOrderBook(String symbol) {
        SymbolOrderBook book = books.get(symbol);
        if (book == null) {
            return new OrderBookSnapshot(List.of(), List.of());
        }
        return book.snapshot(DEFAULT_DEPTH);
    }

    public int getOpenOrderCount(String symbol) {
        SymbolOrderBook book = books.get(symbol);
        if (book == null) {
            return 0;
        }
        return book.countOpenOrders();
    }

    public record MatchResult(Order order, List<Trade> trades) {
    }
}
