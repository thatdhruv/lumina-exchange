package com.lumina.orderbook.engine;

public enum RejectReason {
    DUPLICATE_ORDER_ID,
    INVALID_ORDER,
    POST_ONLY_WOULD_CROSS,
    FOK_UNFILLABLE,
    UNKNOWN_ORDER,
    NOT_ORDER_OWNER
}
