package com.lumina.orderbook.engine;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.domain.Trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

final class SymbolOrderBook {

    private static final Comparator<Order> BID_COMPARATOR = Comparator
            .comparing(Order::getPrice)
            .reversed()
            .thenComparing(Order::getCreatedAt);

    private static final Comparator<Order> ASK_COMPARATOR = Comparator
            .comparing(Order::getPrice)
            .thenComparing(Order::getCreatedAt);

    private final PriorityQueue<Order> bids = new PriorityQueue<>(BID_COMPARATOR);
    private final PriorityQueue<Order> asks = new PriorityQueue<>(ASK_COMPARATOR);
    private final ReentrantLock lock = new ReentrantLock();

    List<Trade> placeOrder(Order incoming) {
        lock.lock();
        try {
            List<Trade> trades = match(incoming);
            if (incoming.isActive() && incoming.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                if (incoming.getType() == OrderType.MARKET) {
                    incoming.setStatus(OrderStatus.CANCELLED);
                } else {
                    addToBook(incoming);
                }
            }
            return trades;
        } finally {
            lock.unlock();
        }
    }

    boolean cancelOrder(UUID orderId) {
        lock.lock();
        try {
            Order bidOrder = removeOrder(bids, orderId);
            if (bidOrder != null) {
                bidOrder.setStatus(OrderStatus.CANCELLED);
                return true;
            }
            Order askOrder = removeOrder(asks, orderId);
            if (askOrder != null) {
                askOrder.setStatus(OrderStatus.CANCELLED);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    int countOpenOrders() {
        lock.lock();
        try {
            return (int) bids.stream().filter(Order::isActive).count()
                    + (int) asks.stream().filter(Order::isActive).count();
        } finally {
            lock.unlock();
        }
    }

    OrderBookSnapshot snapshot(int depth) {
        lock.lock();
        try {
            purgeInactive(bids);
            purgeInactive(asks);
            return new OrderBookSnapshot(
                    aggregateLevels(bids, depth, true),
                    aggregateLevels(asks, depth, false));
        } finally {
            lock.unlock();
        }
    }

    private List<Trade> match(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        PriorityQueue<Order> opposite = incoming.getSide() == OrderSide.BUY ? asks : bids;

        while (incoming.isActive()
                && incoming.getQuantity().compareTo(BigDecimal.ZERO) > 0
                && !opposite.isEmpty()) {
            purgeHeadIfInactive(opposite);
            if (opposite.isEmpty()) {
                break;
            }

            Order resting = opposite.peek();
            if (!canMatch(incoming, resting)) {
                break;
            }

            opposite.poll();
            BigDecimal fillQty = incoming.getQuantity().min(resting.getQuantity());
            BigDecimal tradePrice = resting.getPrice();

            applyFill(incoming, fillQty);
            applyFill(resting, fillQty);

            UUID buyOrderId = incoming.getSide() == OrderSide.BUY ? incoming.getId() : resting.getId();
            UUID sellOrderId = incoming.getSide() == OrderSide.SELL ? incoming.getId() : resting.getId();
            trades.add(Trade.create(buyOrderId, sellOrderId, incoming.getSymbol(), tradePrice, fillQty));

            if (resting.isActive()) {
                opposite.offer(resting);
            }
        }

        return trades;
    }

    private boolean canMatch(Order incoming, Order resting) {
        if (incoming.getType() == OrderType.MARKET) {
            return true;
        }
        if (incoming.getSide() == OrderSide.BUY) {
            return incoming.getPrice().compareTo(resting.getPrice()) >= 0;
        }
        return incoming.getPrice().compareTo(resting.getPrice()) <= 0;
    }

    private void applyFill(Order order, BigDecimal fillQty) {
        BigDecimal remaining = order.getQuantity().subtract(fillQty);
        order.setQuantity(remaining);
        if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            order.setStatus(OrderStatus.FILLED);
        } else {
            order.setStatus(OrderStatus.PARTIAL);
        }
    }

    private void addToBook(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            bids.offer(order);
        } else {
            asks.offer(order);
        }
    }

    private Order removeOrder(PriorityQueue<Order> book, UUID orderId) {
        List<Order> retained = new ArrayList<>();
        Order removed = null;
        while (!book.isEmpty()) {
            Order order = book.poll();
            if (order.getId().equals(orderId) && order.isActive()) {
                removed = order;
            } else if (order.isActive()) {
                retained.add(order);
            }
        }
        book.addAll(retained);
        return removed;
    }

    private void purgeInactive(PriorityQueue<Order> book) {
        List<Order> active = book.stream().filter(Order::isActive).collect(Collectors.toCollection(ArrayList::new));
        book.clear();
        book.addAll(active);
    }

    private void purgeHeadIfInactive(PriorityQueue<Order> book) {
        while (!book.isEmpty() && !book.peek().isActive()) {
            book.poll();
        }
    }

    private List<PriceLevel> aggregateLevels(PriorityQueue<Order> book, int depth, boolean descending) {
        return book.stream()
                .filter(Order::isActive)
                .collect(Collectors.groupingBy(
                        Order::getPrice,
                        Collectors.reducing(BigDecimal.ZERO, Order::getQuantity, BigDecimal::add)))
                .entrySet()
                .stream()
                .sorted(descending
                        ? Map.Entry.<BigDecimal, BigDecimal>comparingByKey().reversed()
                        : Map.Entry.comparingByKey())
                .limit(depth)
                .map(entry -> new PriceLevel(entry.getKey(), entry.getValue()))
                .toList();
    }
}
