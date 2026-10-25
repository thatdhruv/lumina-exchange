package com.lumina.orderbook.engine;

public enum TimeInForce {
    /** Rest on the book until filled or cancelled. */
    GTC,
    /** Fill what is available now, cancel the remainder. */
    IOC,
    /** Fill completely and immediately, or reject. */
    FOK
}
