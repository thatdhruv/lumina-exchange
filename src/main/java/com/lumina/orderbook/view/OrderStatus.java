package com.lumina.orderbook.view;

public enum OrderStatus {
    OPEN,
    PARTIAL,
    FILLED,
    CANCELLED,
    REJECTED;

    public boolean isActive() {
        return this == OPEN || this == PARTIAL;
    }
}
