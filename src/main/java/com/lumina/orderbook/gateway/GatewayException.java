package com.lumina.orderbook.gateway;

/** Business-level failure raised by the gateway; mapped to HTTP and gRPC statuses at the edges. */
public class GatewayException extends RuntimeException {

    public enum Kind {
        INVALID,
        NOT_FOUND,
        CONFLICT,
        RATE_LIMITED,
        TIMEOUT,
        UNAVAILABLE
    }

    private final Kind kind;
    private final String code;

    private GatewayException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public static GatewayException invalid(String code, String message) {
        return new GatewayException(Kind.INVALID, code, message);
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(Kind.NOT_FOUND, "NOT_FOUND", message);
    }

    public static GatewayException conflict(String code, String message) {
        return new GatewayException(Kind.CONFLICT, code, message);
    }

    public static GatewayException rateLimited() {
        return new GatewayException(Kind.RATE_LIMITED, "RATE_LIMITED", "too many requests");
    }

    public static GatewayException timeout(String message) {
        return new GatewayException(Kind.TIMEOUT, "COMMAND_TIMEOUT", message);
    }

    public static GatewayException unavailable(String message) {
        return new GatewayException(Kind.UNAVAILABLE, "UNAVAILABLE", message);
    }
}
