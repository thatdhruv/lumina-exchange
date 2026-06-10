package com.lumina.orderbook.engine;

import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderStatus;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.domain.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEngineTest {

    private static final String SYMBOL = "BTC-USD";

    private MatchingEngine matchingEngine;
    private final List<Trade> allTrades = new ArrayList<>();

    @BeforeEach
    void setUp() {
        matchingEngine = new MatchingEngine();
        allTrades.clear();
    }

    @Test
    void noMatch_limitBuyBelowBestAsk_restingOnBook() {
        placeLimit(OrderSide.SELL, "51000", "1");
        Order buy = placeLimit(OrderSide.BUY, "50000", "1");

        assertEquals(OrderStatus.OPEN, buy.getStatus());
        assertEquals(new BigDecimal("1"), buy.getQuantity());
        assertTrue(allTrades.isEmpty());

        OrderBookSnapshot book = matchingEngine.getOrderBook(SYMBOL);
        assertEquals(1, book.bids().size());
        assertEquals(1, book.asks().size());
        assertEquals(new BigDecimal("50000"), book.bids().getFirst().price());
        assertEquals(new BigDecimal("51000"), book.asks().getFirst().price());
    }

    @Test
    void fullFill_limitBuyCrossesAsk() {
        Order sell = placeLimit(OrderSide.SELL, "50000", "2");
        Order buy = placeLimit(OrderSide.BUY, "50000", "2");

        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(1, allTrades.size());
        assertTrade(allTrades.getFirst(), buy.getId(), sell.getId(), "50000", "2");
    }

    @Test
    void partialFill_incomingBuyLargerThanAsk() {
        Order sell = placeLimit(OrderSide.SELL, "50000", "1");
        Order buy = placeLimit(OrderSide.BUY, "50000", "3");

        assertEquals(OrderStatus.PARTIAL, buy.getStatus());
        assertEquals(new BigDecimal("2"), buy.getQuantity());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(1, allTrades.size());
        assertTrade(allTrades.getFirst(), buy.getId(), sell.getId(), "50000", "1");

        OrderBookSnapshot book = matchingEngine.getOrderBook(SYMBOL);
        assertEquals(1, book.bids().size());
        assertEquals(new BigDecimal("2"), book.bids().getFirst().quantity());
        assertTrue(book.asks().isEmpty());
    }

    @Test
    void partialFill_multipleTradesUntilFilled() {
        Order sell1 = placeLimit(OrderSide.SELL, "50000", "1");
        Order sell2 = placeLimit(OrderSide.SELL, "50100", "1");
        Order buy = placeLimit(OrderSide.BUY, "50100", "2");

        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell1.getStatus());
        assertEquals(OrderStatus.FILLED, sell2.getStatus());
        assertEquals(2, allTrades.size());
        assertTrade(allTrades.get(0), buy.getId(), sell1.getId(), "50000", "1");
        assertTrade(allTrades.get(1), buy.getId(), sell2.getId(), "50100", "1");
    }

    @Test
    void partialFill_restingSellLargerThanIncomingBuy() {
        Order sell = placeLimit(OrderSide.SELL, "50000", "5");
        Order buy = placeLimit(OrderSide.BUY, "50000", "2");

        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.PARTIAL, sell.getStatus());
        assertEquals(new BigDecimal("3"), sell.getQuantity());
        assertEquals(1, allTrades.size());
    }

    @Test
    void marketBuy_fillsAtBestAsk() {
        placeLimit(OrderSide.SELL, "51000", "1");
        placeLimit(OrderSide.SELL, "50000", "2");
        Order marketBuy = placeMarket(OrderSide.BUY, "3");

        assertEquals(OrderStatus.FILLED, marketBuy.getStatus());
        assertEquals(2, allTrades.size());
        assertEquals(new BigDecimal("50000"), allTrades.get(0).getPrice());
        assertEquals(new BigDecimal("51000"), allTrades.get(1).getPrice());
    }

    @Test
    void marketBuy_unfilledRemainderCancelled() {
        placeLimit(OrderSide.SELL, "50000", "1");
        Order marketBuy = placeMarket(OrderSide.BUY, "2");

        assertEquals(OrderStatus.CANCELLED, marketBuy.getStatus());
        assertEquals(new BigDecimal("1"), marketBuy.getQuantity());
        assertEquals(1, allTrades.size());
    }

    @Test
    void marketSell_fillsAtBestBid() {
        placeLimit(OrderSide.BUY, "49000", "1");
        placeLimit(OrderSide.BUY, "50000", "1");
        Order marketSell = placeMarket(OrderSide.SELL, "2");

        assertEquals(OrderStatus.FILLED, marketSell.getStatus());
        assertEquals(2, allTrades.size());
        assertEquals(new BigDecimal("50000"), allTrades.get(0).getPrice());
        assertEquals(new BigDecimal("49000"), allTrades.get(1).getPrice());
    }

    @Test
    void cancelOrder_removesRestingBid() {
        Order buy = placeLimit(OrderSide.BUY, "50000", "1");
        assertTrue(matchingEngine.cancelOrder(SYMBOL, buy.getId()));
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertTrue(matchingEngine.getOrderBook(SYMBOL).bids().isEmpty());
    }

    @Test
    void cancelOrder_removesRestingAsk() {
        Order sell = placeLimit(OrderSide.SELL, "50000", "1");
        assertTrue(matchingEngine.cancelOrder(SYMBOL, sell.getId()));
        assertEquals(OrderStatus.CANCELLED, sell.getStatus());
        assertTrue(matchingEngine.getOrderBook(SYMBOL).asks().isEmpty());
    }

    @Test
    void cancelOrder_unknownSymbolOrIdReturnsFalse() {
        Order buy = placeLimit(OrderSide.BUY, "50000", "1");
        assertFalse(matchingEngine.cancelOrder("ETH-USD", buy.getId()));
        assertFalse(matchingEngine.cancelOrder(SYMBOL, UUID.randomUUID()));
    }

    @Test
    void matchWhenBestBidGreaterOrEqualBestAsk() {
        placeLimit(OrderSide.BUY, "52000", "1");
        Order sell = placeLimit(OrderSide.SELL, "51000", "1");

        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(1, allTrades.size());
        assertEquals(new BigDecimal("52000"), allTrades.getFirst().getPrice());
    }

    @Test
    void orderBookAggregatesPriceLevels() {
        placeLimit(OrderSide.BUY, "50000", "1");
        placeLimit(OrderSide.BUY, "50000", "2");
        placeLimit(OrderSide.BUY, "49000", "1");
        placeLimit(OrderSide.SELL, "51000", "1");
        placeLimit(OrderSide.SELL, "51000", "3");

        OrderBookSnapshot book = matchingEngine.getOrderBook(SYMBOL);
        assertEquals(2, book.bids().size());
        assertEquals(new BigDecimal("3"), book.bids().getFirst().quantity());
        assertEquals(new BigDecimal("50000"), book.bids().getFirst().price());
        assertEquals(1, book.asks().size());
        assertEquals(new BigDecimal("4"), book.asks().getFirst().quantity());
    }

    @Test
    void emptyOrderBookForUnknownSymbol() {
        OrderBookSnapshot book = matchingEngine.getOrderBook("ETH-USD");
        assertTrue(book.bids().isEmpty());
        assertTrue(book.asks().isEmpty());
    }

    @Test
    void getOpenOrderCount_tracksRestingOrders() {
        assertEquals(0, matchingEngine.getOpenOrderCount(SYMBOL));
        placeLimit(OrderSide.BUY, "50000", "1");
        placeLimit(OrderSide.SELL, "51000", "2");
        assertEquals(2, matchingEngine.getOpenOrderCount(SYMBOL));
    }

    @Test
    void symbolsAreIsolated() {
        Order btcSell = placeLimitOnSymbol("BTC-USD", OrderSide.SELL, "50000", "1");
        Order ethBuy = placeLimitOnSymbol("ETH-USD", OrderSide.BUY, "3000", "1");

        assertEquals(OrderStatus.OPEN, btcSell.getStatus());
        assertEquals(OrderStatus.OPEN, ethBuy.getStatus());
        assertTrue(allTrades.isEmpty());
    }

    private Order placeLimit(OrderSide side, String price, String quantity) {
        return placeLimitOnSymbol(SYMBOL, side, price, quantity);
    }

    private Order placeLimitOnSymbol(String symbol, OrderSide side, String price, String quantity) {
        Order order = Order.newOpen(
                symbol,
                side,
                OrderType.LIMIT,
                new BigDecimal(price),
                new BigDecimal(quantity));
        submit(order);
        return order;
    }

    private Order placeMarket(OrderSide side, String quantity) {
        Order order = Order.newOpen(
                SYMBOL,
                side,
                OrderType.MARKET,
                BigDecimal.ZERO,
                new BigDecimal(quantity));
        submit(order);
        return order;
    }

    private void submit(Order order) {
        allTrades.addAll(matchingEngine.placeOrder(order).trades());
    }

    private void assertTrade(
            Trade trade,
            UUID buyOrderId,
            UUID sellOrderId,
            String price,
            String quantity) {
        assertEquals(buyOrderId, trade.getBuyOrderId());
        assertEquals(sellOrderId, trade.getSellOrderId());
        assertEquals(SYMBOL, trade.getSymbol());
        assertEquals(0, trade.getPrice().compareTo(new BigDecimal(price)));
        assertEquals(0, trade.getQuantity().compareTo(new BigDecimal(quantity)));
    }
}
