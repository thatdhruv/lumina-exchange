package com.lumina.orderbook.service;

import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.engine.MatchingEngine;
import com.lumina.orderbook.engine.OrderBookSnapshot;
import com.lumina.orderbook.exception.OrderNotFoundException;
import com.lumina.orderbook.kafka.OrderEventPublisher;
import com.lumina.orderbook.metrics.ExchangeMetrics;
import com.lumina.orderbook.store.OrderStore;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderService {

    private final MatchingEngine matchingEngine;
    private final OrderStore orderStore;
    private final OrderEventPublisher orderEventPublisher;
    private final ExchangeMetrics exchangeMetrics;

    public OrderService(
            MatchingEngine matchingEngine,
            OrderStore orderStore,
            OrderEventPublisher orderEventPublisher,
            ExchangeMetrics exchangeMetrics) {
        this.matchingEngine = matchingEngine;
        this.orderStore = orderStore;
        this.orderEventPublisher = orderEventPublisher;
        this.exchangeMetrics = exchangeMetrics;
    }

    public Order placeOrder(PlaceOrderRequest request) {
        validateRequest(request);

        BigDecimal price = request.type() == OrderType.MARKET ? BigDecimal.ZERO : request.price();
        Order order = Order.newOpen(
                request.symbol(),
                request.side(),
                request.type(),
                price,
                request.quantity());

        orderStore.saveOrder(order);

        Timer.Sample matchingSample = exchangeMetrics.startMatchingTimer();
        MatchingEngine.MatchResult result = matchingEngine.placeOrder(order);
        exchangeMetrics.recordMatchingLatency(matchingSample, order.getSymbol());

        orderStore.saveTrades(result.trades());

        orderEventPublisher.publishOrderPlaced(order);
        orderEventPublisher.publishTradesExecuted(result.trades());

        exchangeMetrics.recordOrderPlaced(order);
        exchangeMetrics.recordTradesExecuted(result.trades());

        return order;
    }

    public Order cancelOrder(UUID orderId) {
        Order order = orderStore.findOrder(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.isActive()) {
            return order;
        }

        boolean removed = matchingEngine.cancelOrder(order.getSymbol(), orderId);
        if (removed) {
            order.setStatus(OrderStatus.CANCELLED);
            orderEventPublisher.publishOrderCancelled(order);
            exchangeMetrics.recordOrderCancelled(order);
        }
        return order;
    }

    public Order getOrder(UUID orderId) {
        return orderStore.findOrder(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public OrderBookSnapshot getOrderBook(String symbol) {
        return matchingEngine.getOrderBook(symbol);
    }

    private void validateRequest(PlaceOrderRequest request) {
        if (request.type() == OrderType.LIMIT
                && (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("Limit orders require a positive price");
        }
        if (request.quantity() == null || request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }
}
