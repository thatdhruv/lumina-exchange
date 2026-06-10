package com.lumina.orderbook.store;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.Trade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class OrderStore {

    private final ConcurrentMap<UUID, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Trade> trades = new ConcurrentHashMap<>();

    public void saveOrder(Order order) {
        orders.put(order.getId(), order);
    }

    public Optional<Order> findOrder(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    public void saveTrades(List<Trade> newTrades) {
        for (Trade trade : newTrades) {
            trades.put(trade.getId(), trade);
        }
    }

    public List<Trade> findTradesForOrder(UUID orderId) {
        List<Trade> result = new ArrayList<>();
        for (Trade trade : trades.values()) {
            if (trade.getBuyOrderId().equals(orderId) || trade.getSellOrderId().equals(orderId)) {
                result.add(trade);
            }
        }
        return result;
    }
}
