package com.lumina.orderbook.service;

import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.engine.MatchingEngine;
import com.lumina.orderbook.exception.OrderNotFoundException;
import com.lumina.orderbook.kafka.OrderEventPublisher;
import com.lumina.orderbook.metrics.ExchangeMetrics;
import com.lumina.orderbook.store.OrderStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private MatchingEngine matchingEngine;

    @Mock
    private OrderStore orderStore;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private ExchangeMetrics exchangeMetrics;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUpMetrics() {
        when(exchangeMetrics.startMatchingTimer()).thenReturn(Timer.start(meterRegistry));
    }

    @Test
    void placeOrder_persistsOrderAndTrades() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                "BTC-USD",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("50000"),
                new BigDecimal("1"));

        when(matchingEngine.placeOrder(any(Order.class)))
                .thenAnswer(invocation -> new MatchingEngine.MatchResult(invocation.getArgument(0), List.of()));

        Order placed = orderService.placeOrder(request);

        assertEquals("BTC-USD", placed.getSymbol());
        assertEquals(OrderStatus.OPEN, placed.getStatus());
        verify(orderStore).saveOrder(placed);
        verify(orderStore).saveTrades(List.of());
        verify(orderEventPublisher).publishOrderPlaced(placed);
        verify(orderEventPublisher).publishTradesExecuted(List.of());
    }

    @Test
    void cancelOrder_notFoundThrows() {
        UUID id = UUID.randomUUID();
        when(orderStore.findOrder(id)).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder(id));
    }

    @Test
    void cancelOrder_alreadyFilledDoesNotCallEngine() {
        UUID id = UUID.randomUUID();
        Order filled = new Order(
                id,
                "BTC-USD",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("50000"),
                BigDecimal.ZERO,
                OrderStatus.FILLED,
                java.time.Instant.now());
        when(orderStore.findOrder(id)).thenReturn(Optional.of(filled));

        Order result = orderService.cancelOrder(id);

        assertEquals(OrderStatus.FILLED, result.getStatus());
        verify(matchingEngine, never()).cancelOrder(any(), any());
    }
}
