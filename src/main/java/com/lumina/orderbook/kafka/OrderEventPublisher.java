package com.lumina.orderbook.kafka;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.Trade;
import com.lumina.orderbook.event.KafkaTopics;
import com.lumina.orderbook.event.OrderCancelledEvent;
import com.lumina.orderbook.event.OrderPlacedEvent;
import com.lumina.orderbook.event.TradeExecutedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderPlaced(Order order) {
        OrderPlacedEvent event = OrderPlacedEvent.from(order);
        kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, order.getId().toString(), event);
    }

    public void publishOrderCancelled(Order order) {
        OrderCancelledEvent event = OrderCancelledEvent.from(order);
        kafkaTemplate.send(KafkaTopics.ORDERS_CANCELLED, order.getId().toString(), event);
    }

    public void publishTradesExecuted(List<Trade> trades) {
        for (Trade trade : trades) {
            TradeExecutedEvent event = TradeExecutedEvent.from(trade);
            kafkaTemplate.send(KafkaTopics.TRADES_EXECUTED, trade.getId().toString(), event);
        }
    }
}
