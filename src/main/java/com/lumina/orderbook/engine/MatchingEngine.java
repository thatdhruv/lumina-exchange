package com.lumina.orderbook.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All order books owned by one command-topic partition. Not thread-safe: exactly one thread (the
 * partition consumer) calls {@link #process}.
 */
public final class MatchingEngine {

    public static final int DEFAULT_RECENT_ID_CAPACITY = 50_000;

    private final int recentIdCapacity;
    private final Map<String, OrderBook> books = new HashMap<>();

    public MatchingEngine() {
        this(DEFAULT_RECENT_ID_CAPACITY);
    }

    public MatchingEngine(int recentIdCapacity) {
        this.recentIdCapacity = recentIdCapacity;
    }

    public List<EngineEvent> process(EngineCommand command) {
        List<EngineEvent> events = new ArrayList<>(4);
        books.computeIfAbsent(command.symbol(), s -> new OrderBook(s, recentIdCapacity)).process(command, events);
        return events;
    }

    public OrderBook book(String symbol) {
        return books.get(symbol);
    }

    public List<BookState> snapshot() {
        return books.values().stream().map(OrderBook::state).toList();
    }

    public void restore(List<BookState> states) {
        books.clear();
        for (BookState state : states) {
            books.put(state.symbol(), OrderBook.restore(state, recentIdCapacity));
        }
    }
}
