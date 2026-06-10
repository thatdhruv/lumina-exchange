package com.lumina.orderbook.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.engine.OrderBookSnapshot;
import com.lumina.orderbook.engine.PriceLevel;
import com.lumina.orderbook.exception.OrderNotFoundException;
import com.lumina.orderbook.service.OrderService;
import com.lumina.orderbook.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    void placeOrder_returnsCreatedOrder() throws Exception {
        UUID id = UUID.randomUUID();
        Order order = new Order(
                id,
                "BTC-USD",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("50000"),
                new BigDecimal("1"),
                OrderStatus.OPEN,
                Instant.parse("2024-01-01T00:00:00Z"));
        when(orderService.placeOrder(any(PlaceOrderRequest.class))).thenReturn(order);

        PlaceOrderRequest request = new PlaceOrderRequest(
                "BTC-USD",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("50000"),
                new BigDecimal("1"));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void getOrder_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.getOrder(id)).thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOrderBook_returnsLevels() throws Exception {
        when(orderService.getOrderBook("BTC-USD"))
                .thenReturn(new OrderBookSnapshot(
                        List.of(new PriceLevel(new BigDecimal("50000"), new BigDecimal("2"))),
                        List.of(new PriceLevel(new BigDecimal("51000"), new BigDecimal("1")))));

        mockMvc.perform(get("/api/v1/orderbook/{symbol}", "BTC-USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.bids[0].price").value(50000))
                .andExpect(jsonPath("$.asks[0].price").value(51000));
    }

    @Test
    void cancelOrder_returnsCancelledOrder() throws Exception {
        UUID id = UUID.randomUUID();
        Order cancelled = new Order(
                id,
                "BTC-USD",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("50000"),
                new BigDecimal("1"),
                OrderStatus.CANCELLED,
                Instant.now());
        when(orderService.cancelOrder(id)).thenReturn(cancelled);

        mockMvc.perform(delete("/api/v1/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
