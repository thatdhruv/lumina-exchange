package com.lumina.orderbook.api;

import com.lumina.orderbook.api.dto.ApiError;
import com.lumina.orderbook.api.dto.OrderResponse;
import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.gateway.OrderGateway;
import com.lumina.orderbook.gateway.PlaceOutcome;
import com.lumina.orderbook.gateway.PlaceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@ConditionalOnRole("api")
public class OrderController {

    private final OrderGateway gateway;

    public OrderController(OrderGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Send {@code Idempotency-Key} to make retries safe: the key (scoped to your account) fixes the
     * order id, and a repeat returns the original order with 200 instead of placing a second one.
     */
    @PostMapping
    public ResponseEntity<?> place(
            Authentication auth,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest body) {
        PlaceRequest request = new PlaceRequest(body.symbol(), body.side(), body.type(), body.timeInForce(),
                Boolean.TRUE.equals(body.postOnly()), body.price(), body.quantity());

        return switch (gateway.place(auth.getName(), request, idempotencyKey)) {
            case PlaceOutcome.Placed placed ->
                    ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(placed.order()));
            case PlaceOutcome.Duplicate dup -> dup.existing()
                    .<ResponseEntity<?>>map(o -> ResponseEntity.ok(OrderResponse.from(o)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                            "DUPLICATE_PENDING", "order " + dup.orderId() + " was already submitted; retry the lookup shortly")));
            case PlaceOutcome.Rejected rejected ->
                    ResponseEntity.unprocessableEntity().body(new ApiError(rejected.reason().name(),
                            "order " + rejected.orderId() + " rejected: " + rejected.reason()));
        };
    }

    @GetMapping("/{id}")
    public OrderResponse get(Authentication auth, @PathVariable UUID id) {
        return OrderResponse.from(gateway.find(auth.getName(), id));
    }

    @DeleteMapping("/{id}")
    public OrderResponse cancel(Authentication auth, @PathVariable UUID id) {
        return OrderResponse.from(gateway.cancel(auth.getName(), id));
    }

    @GetMapping
    public List<OrderResponse> open(Authentication auth, @RequestParam(required = false) String symbol) {
        return gateway.openOrders(auth.getName(), symbol).stream().map(OrderResponse::from).toList();
    }
}
