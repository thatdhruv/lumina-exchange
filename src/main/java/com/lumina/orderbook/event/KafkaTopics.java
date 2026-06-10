package com.lumina.orderbook.event;

public final class KafkaTopics {

    public static final String ORDERS_PLACED = "orders.placed";
    public static final String ORDERS_CANCELLED = "orders.cancelled";
    public static final String TRADES_EXECUTED = "trades.executed";

    private KafkaTopics() {
    }
}
