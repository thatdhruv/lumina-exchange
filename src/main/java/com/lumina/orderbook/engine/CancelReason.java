package com.lumina.orderbook.engine;

public enum CancelReason {
    USER,
    /** Market or IOC order whose remainder found no liquidity. */
    UNFILLED_REMAINDER,
    /** Incoming order would have traded against the same account's resting order. */
    SELF_TRADE_PREVENTED
}
