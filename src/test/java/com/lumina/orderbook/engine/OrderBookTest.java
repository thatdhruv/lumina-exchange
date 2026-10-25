package com.lumina.orderbook.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {

    private OrderBook book;
    private long clock;

    @BeforeEach
    void setUp() {
        book = new OrderBook("BTC-USD", 1_000);
        clock = 0;
    }

    private static UUID id(long n) {
        return new UUID(0, n);
    }

    private List<EngineEvent> limit(long id, String acct, Side side, long price, long qty) {
        return place(id, acct, side, OrderType.LIMIT, TimeInForce.GTC, false, price, qty);
    }

    private List<EngineEvent> place(long id, String acct, Side side, OrderType type, TimeInForce tif,
                                    boolean postOnly, long price, long qty) {
        List<EngineEvent> out = new ArrayList<>();
        book.process(new EngineCommand.Place("BTC-USD", id(id), acct, side, type, tif, postOnly, price, qty, ++clock), out);
        return out;
    }

    private List<EngineEvent> cancel(long id, String acct) {
        List<EngineEvent> out = new ArrayList<>();
        book.process(new EngineCommand.Cancel("BTC-USD", id(id), acct, ++clock), out);
        return out;
    }

    private static List<EngineEvent.Trade> trades(List<EngineEvent> events) {
        return events.stream().filter(EngineEvent.Trade.class::isInstance).map(EngineEvent.Trade.class::cast).toList();
    }

    private static <T extends EngineEvent> T only(List<EngineEvent> events, Class<T> type) {
        List<T> found = events.stream().filter(type::isInstance).map(type::cast).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    @Test
    void limitOrderRestsWhenNothingCrosses() {
        List<EngineEvent> events = limit(1, "a", Side.BUY, 100, 5);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(EngineEvent.Accepted.class);
        assertThat(only(events, EngineEvent.BookChanged.class).levels())
                .containsExactly(new EngineEvent.LevelChange(Side.BUY, 100, 5));
        assertThat(book.bestBid()).isEqualTo(100);
        assertThat(book.bestAsk()).isZero();
    }

    @Test
    void tradesAtMakerPriceNotTakerPrice() {
        limit(1, "a", Side.SELL, 100, 5);

        List<EngineEvent> events = limit(2, "b", Side.BUY, 105, 5);

        EngineEvent.Trade trade = only(events, EngineEvent.Trade.class);
        assertThat(trade.priceTicks()).isEqualTo(100);
        assertThat(trade.quantityLots()).isEqualTo(5);
        assertThat(trade.takerOrderId()).isEqualTo(id(2));
        assertThat(trade.makerOrderId()).isEqualTo(id(1));
        assertThat(trade.buyOrderId()).isEqualTo(id(2));
        assertThat(trade.sellOrderId()).isEqualTo(id(1));
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    void timePriorityWithinALevel() {
        limit(1, "a", Side.SELL, 100, 3);
        limit(2, "b", Side.SELL, 100, 3);

        List<EngineEvent.Trade> trades = trades(limit(3, "c", Side.BUY, 100, 4));

        assertThat(trades).extracting(EngineEvent.Trade::makerOrderId).containsExactly(id(1), id(2));
        assertThat(trades).extracting(EngineEvent.Trade::quantityLots).containsExactly(3L, 1L);
        assertThat(book.depth(Side.SELL, 5)).containsExactly(new EngineEvent.LevelChange(Side.SELL, 100, 2));
    }

    @Test
    void betterPriceBeatsEarlierWorsePrice() {
        limit(1, "a", Side.SELL, 101, 1);
        limit(2, "b", Side.SELL, 100, 1);

        List<EngineEvent.Trade> trades = trades(limit(3, "c", Side.BUY, 101, 2));

        assertThat(trades).extracting(EngineEvent.Trade::makerOrderId).containsExactly(id(2), id(1));
        assertThat(trades).extracting(EngineEvent.Trade::priceTicks).containsExactly(100L, 101L);
    }

    @Test
    void partialFillRestsTheRemainder() {
        limit(1, "a", Side.SELL, 100, 2);

        List<EngineEvent> events = limit(2, "b", Side.BUY, 100, 5);

        assertThat(trades(events)).hasSize(1);
        assertThat(book.depth(Side.BUY, 5)).containsExactly(new EngineEvent.LevelChange(Side.BUY, 100, 3));
        assertThat(only(events, EngineEvent.BookChanged.class).levels()).containsExactly(
                new EngineEvent.LevelChange(Side.BUY, 100, 3),
                new EngineEvent.LevelChange(Side.SELL, 100, 0));
    }

    @Test
    void marketOrderSweepsLevelsAndCancelsRemainder() {
        limit(1, "a", Side.SELL, 100, 2);
        limit(2, "a", Side.SELL, 110, 2);

        List<EngineEvent> events = place(3, "b", Side.BUY, OrderType.MARKET, TimeInForce.GTC, false, 0, 10);

        assertThat(trades(events)).extracting(EngineEvent.Trade::priceTicks).containsExactly(100L, 110L);
        EngineEvent.Cancelled cancelled = only(events, EngineEvent.Cancelled.class);
        assertThat(cancelled.remainingLots()).isEqualTo(6);
        assertThat(cancelled.reason()).isEqualTo(CancelReason.UNFILLED_REMAINDER);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    void marketOrderOnEmptyBookIsCancelledNotRested() {
        List<EngineEvent> events = place(1, "a", Side.SELL, OrderType.MARKET, TimeInForce.GTC, false, 0, 1);

        assertThat(only(events, EngineEvent.Cancelled.class).remainingLots()).isEqualTo(1);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    void iocCancelsUnfilledRemainder() {
        limit(1, "a", Side.SELL, 100, 2);

        List<EngineEvent> events = place(2, "b", Side.BUY, OrderType.LIMIT, TimeInForce.IOC, false, 100, 5);

        assertThat(trades(events)).hasSize(1);
        assertThat(only(events, EngineEvent.Cancelled.class).remainingLots()).isEqualTo(3);
        assertThat(book.depth(Side.BUY, 5)).isEmpty();
    }

    @Test
    void fokFillsCompletelyAcrossLevels() {
        limit(1, "a", Side.SELL, 100, 2);
        limit(2, "a", Side.SELL, 101, 3);

        List<EngineEvent> events = place(3, "b", Side.BUY, OrderType.LIMIT, TimeInForce.FOK, false, 101, 5);

        assertThat(trades(events)).hasSize(2);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    void fokRejectsWithoutTouchingTheBookWhenShort() {
        limit(1, "a", Side.SELL, 100, 2);
        limit(2, "a", Side.SELL, 102, 3);

        List<EngineEvent> events = place(3, "b", Side.BUY, OrderType.LIMIT, TimeInForce.FOK, false, 101, 5);

        assertThat(only(events, EngineEvent.Rejected.class).reason()).isEqualTo(RejectReason.FOK_UNFILLABLE);
        assertThat(trades(events)).isEmpty();
        assertThat(book.depth(Side.SELL, 5)).hasSize(2);
    }

    @Test
    void postOnlyRejectedWhenItWouldCross() {
        limit(1, "a", Side.SELL, 100, 1);

        List<EngineEvent> events = place(2, "b", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, true, 100, 1);

        assertThat(only(events, EngineEvent.Rejected.class).reason()).isEqualTo(RejectReason.POST_ONLY_WOULD_CROSS);
        assertThat(book.depth(Side.SELL, 5)).hasSize(1);
    }

    @Test
    void postOnlyRestsWhenItDoesNotCross() {
        limit(1, "a", Side.SELL, 100, 1);

        List<EngineEvent> events = place(2, "b", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, true, 99, 1);

        assertThat(events).hasAtLeastOneElementOfType(EngineEvent.Accepted.class);
        assertThat(book.bestBid()).isEqualTo(99);
    }

    @Test
    void postOnlyWithIocIsInvalid() {
        List<EngineEvent> events = place(1, "a", Side.BUY, OrderType.LIMIT, TimeInForce.IOC, true, 99, 1);

        assertThat(only(events, EngineEvent.Rejected.class).reason()).isEqualTo(RejectReason.INVALID_ORDER);
    }

    @Test
    void selfTradeCancelsTheIncomingRemainderAndLeavesRestingOrderAlone() {
        limit(1, "a", Side.SELL, 100, 2);
        limit(2, "b", Side.SELL, 100, 2);

        // a is first in the queue and is the taker's own order, so nothing trades
        List<EngineEvent> events = limit(3, "a", Side.BUY, 100, 3);

        assertThat(trades(events)).isEmpty();
        assertThat(only(events, EngineEvent.Cancelled.class).reason()).isEqualTo(CancelReason.SELF_TRADE_PREVENTED);
        assertThat(book.depth(Side.SELL, 5)).containsExactly(new EngineEvent.LevelChange(Side.SELL, 100, 4));
        assertThat(book.depth(Side.BUY, 5)).isEmpty();
    }

    @Test
    void selfTradeAfterPartialFillKeepsTheFill() {
        limit(1, "b", Side.SELL, 100, 1);
        limit(2, "a", Side.SELL, 100, 5);

        List<EngineEvent> events = limit(3, "a", Side.BUY, 100, 4);

        assertThat(trades(events)).hasSize(1);
        assertThat(only(events, EngineEvent.Cancelled.class).remainingLots()).isEqualTo(3);
    }

    @Test
    void fokIsUnfillableWhenOwnOrderBlocksTheQueue() {
        limit(1, "a", Side.SELL, 100, 5);

        List<EngineEvent> events = place(2, "a", Side.BUY, OrderType.LIMIT, TimeInForce.FOK, false, 100, 5);

        assertThat(only(events, EngineEvent.Rejected.class).reason()).isEqualTo(RejectReason.FOK_UNFILLABLE);
    }

    @Test
    void duplicateOrderIdIsRejectedWhileRestingAndAfterFill() {
        limit(1, "a", Side.SELL, 100, 1);
        assertThat(only(limit(1, "a", Side.SELL, 100, 1), EngineEvent.Rejected.class).reason())
                .isEqualTo(RejectReason.DUPLICATE_ORDER_ID);

        limit(2, "b", Side.BUY, 100, 1);
        assertThat(only(limit(1, "a", Side.SELL, 100, 1), EngineEvent.Rejected.class).reason())
                .isEqualTo(RejectReason.DUPLICATE_ORDER_ID);
        assertThat(book.liveOrderCount()).isZero();
    }

    @Test
    void invalidOrdersAreRejected() {
        assertThat(only(limit(1, "a", Side.BUY, 0, 1), EngineEvent.Rejected.class).reason())
                .isEqualTo(RejectReason.INVALID_ORDER);
        assertThat(only(limit(2, "a", Side.BUY, 10, 0), EngineEvent.Rejected.class).reason())
                .isEqualTo(RejectReason.INVALID_ORDER);
        assertThat(only(limit(3, "", Side.BUY, 10, 1), EngineEvent.Rejected.class).reason())
                .isEqualTo(RejectReason.INVALID_ORDER);
    }

    @Test
    void cancelRemovesTheOrderAndReportsTheLevel() {
        limit(1, "a", Side.BUY, 100, 3);
        limit(2, "b", Side.BUY, 100, 2);

        List<EngineEvent> events = cancel(1, "a");

        EngineEvent.Cancelled cancelled = only(events, EngineEvent.Cancelled.class);
        assertThat(cancelled.remainingLots()).isEqualTo(3);
        assertThat(cancelled.reason()).isEqualTo(CancelReason.USER);
        assertThat(only(events, EngineEvent.BookChanged.class).levels())
                .containsExactly(new EngineEvent.LevelChange(Side.BUY, 100, 2));
    }

    @Test
    void cancellingTheLastOrderEmptiesTheLevel() {
        limit(1, "a", Side.BUY, 100, 3);

        List<EngineEvent> events = cancel(1, "a");

        assertThat(only(events, EngineEvent.BookChanged.class).levels())
                .containsExactly(new EngineEvent.LevelChange(Side.BUY, 100, 0));
        assertThat(book.bestBid()).isZero();
    }

    @Test
    void cancelMiddleOfQueuePreservesTheRestOfThePriorityOrder() {
        limit(1, "a", Side.SELL, 100, 1);
        limit(2, "b", Side.SELL, 100, 1);
        limit(3, "c", Side.SELL, 100, 1);
        cancel(2, "b");

        List<EngineEvent.Trade> trades = trades(limit(4, "d", Side.BUY, 100, 2));

        assertThat(trades).extracting(EngineEvent.Trade::makerOrderId).containsExactly(id(1), id(3));
    }

    @Test
    void cancelRejectsUnknownFilledAndForeignOrders() {
        limit(1, "a", Side.SELL, 100, 1);
        limit(2, "b", Side.BUY, 100, 1);

        assertThat(only(cancel(1, "a"), EngineEvent.Rejected.class).reason()).isEqualTo(RejectReason.UNKNOWN_ORDER);
        assertThat(only(cancel(99, "a"), EngineEvent.Rejected.class).reason()).isEqualTo(RejectReason.UNKNOWN_ORDER);

        limit(3, "a", Side.BUY, 90, 1);
        assertThat(only(cancel(3, "mallory"), EngineEvent.Rejected.class).reason())
                .isEqualTo(RejectReason.NOT_ORDER_OWNER);
        assertThat(book.liveOrderCount()).isEqualTo(1);
    }

    @Test
    void sequenceNumbersAreGapFreeAndTradeIdsDeterministic() {
        List<EngineEvent> all = new ArrayList<>();
        all.addAll(limit(1, "a", Side.SELL, 100, 1));
        all.addAll(limit(2, "b", Side.BUY, 100, 1));
        all.addAll(cancel(7, "a"));

        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).seq()).isEqualTo(i + 1L);
        }
        EngineEvent.Trade trade = only(all, EngineEvent.Trade.class);
        assertThat(trade.tradeId()).isEqualTo(
                UUID.nameUUIDFromBytes(("BTC-USD:" + trade.seq()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void recentIdMemoryIsBounded() {
        OrderBook small = new OrderBook("X", 2);
        for (long i = 1; i <= 5; i++) {
            small.process(new EngineCommand.Place("X", id(i), "a", Side.BUY, OrderType.MARKET,
                    TimeInForce.IOC, false, 0, 1, i), new ArrayList<>());
        }

        assertThat(small.state().recentOrderIds()).containsExactly(id(4), id(5));
    }

    @Test
    void stateRoundTripPreservesPriorityAndSequence() {
        limit(1, "a", Side.SELL, 100, 1);
        limit(2, "b", Side.SELL, 100, 1);
        limit(3, "c", Side.BUY, 90, 4);

        OrderBook restored = OrderBook.restore(book.state(), 1_000);

        assertThat(restored.state()).isEqualTo(book.state());
        List<EngineEvent> out = new ArrayList<>();
        restored.process(new EngineCommand.Place("BTC-USD", id(4), "d", Side.BUY, OrderType.LIMIT,
                TimeInForce.GTC, false, 100, 1, 10), out);
        assertThat(trades(out)).extracting(EngineEvent.Trade::makerOrderId).containsExactly(id(1));
        assertThat(restored.lastSeq()).isGreaterThan(book.lastSeq());
    }
}
