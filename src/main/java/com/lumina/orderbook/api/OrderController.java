package com.lumina.orderbook.api;

import com.lumina.orderbook.api.dto.OrderBookResponse;
import com.lumina.orderbook.api.dto.OrderResponse;
import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return OrderResponse.from(orderService.placeOrder(request));
    }

    @DeleteMapping("/orders/{id}")
    public OrderResponse cancelOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.cancelOrder(id));
    }

    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    @GetMapping("/orderbook/{symbol}")
    public OrderBookResponse getOrderBook(@PathVariable String symbol) {
        return OrderBookResponse.from(symbol, orderService.getOrderBook(symbol));
    }
}
