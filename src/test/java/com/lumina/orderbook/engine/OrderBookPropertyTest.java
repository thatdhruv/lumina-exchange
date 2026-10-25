package com.lumina.orderbook.engine;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookPropertyTest {

    private static final String SYMBOL = "BTC-USD";

    @Provide
    Arbitrary<List<EngineCommand>> commands() {
        Arbitrary<Integer> orderIdx = Arbitraries.integers().between(0, 39);
        Arbitrary<String> account = Arbitraries.of("a", "b", "c");

        Arbitrary<EngineCommand> place = Combinators.combine(
                orderIdx,
                account,
                Arbitraries.of(Side.class),
                Arbitraries.frequency(Tuple.of(8, OrderType.LIMIT), Tuple.of(2, OrderType.MARKET)),
                Arbitraries.frequency(Tuple.of(8, TimeInForce.GTC), Tuple.of(2, TimeInForce.IOC), Tuple.of(2, TimeInForce.FOK)),
                Arbitraries.frequency(Tuple.of(9, false), Tuple.of(1, true)),
                Arbitraries.longs().between(-1, 8),
                Arbitraries.longs().between(-1, 10)
        ).as((idx, acct, side, type, tif, postOnly, priceOffset, qty) ->
                (EngineCommand) new EngineCommand.Place(SYMBOL, id(idx), acct, side, type, tif, postOnly,
                        95 + priceOffset, qty, 1L));

        Arbitrary<EngineCommand> cancel = Combinators.combine(orderIdx, account)
                .as((idx, acct) -> (EngineCommand) new EngineCommand.Cancel(SYMBOL, id(idx), acct, 1L));

        return Arbitraries.frequencyOf(Tuple.of(8, place), Tuple.of(3, cancel)).list().ofMinSize(1).ofMaxSize(150);
    }

    private static UUID id(int idx) {
        return new UUID(0, idx);
    }

    @Property
    void behavesLikeTheNaiveReferenceImplementation(@ForAll("commands") List<EngineCommand> commands) {
        OrderBook book = new OrderBook(SYMBOL, 10_000);
        ReferenceBook reference = new ReferenceBook();

        for (EngineCommand command : commands) {
            List<EngineEvent> events = new ArrayList<>();
            book.process(command, events);

            assertThat(ReferenceBook.normalize(events)).isEqualTo(reference.process(command));
        }
        assertThat(book.depth(Side.BUY, 100)).isEqualTo(reference.depth(Side.BUY));
        assertThat(book.depth(Side.SELL, 100)).isEqualTo(reference.depth(Side.SELL));
    }

    @Property
    void bookIsNeverCrossedAndSequenceNeverGaps(@ForAll("commands") List<EngineCommand> commands) {
        OrderBook book = new OrderBook(SYMBOL, 10_000);
        long expectedSeq = 1;

        for (EngineCommand command : commands) {
            List<EngineEvent> events = new ArrayList<>();
            book.process(command, events);
            for (EngineEvent event : events) {
                assertThat(event.seq()).isEqualTo(expectedSeq++);
            }
            if (book.bestBid() != 0 && book.bestAsk() != 0) {
                assertThat(book.bestBid()).isLessThan(book.bestAsk());
            }
        }
        assertThat(book.lastSeq()).isEqualTo(expectedSeq - 1);
    }

    @Property
    void quantityIsConserved(@ForAll("commands") List<EngineCommand> commands) {
        OrderBook book = new OrderBook(SYMBOL, 10_000);
        long accepted = 0;
        long tradedTwice = 0;
        long cancelled = 0;

        for (EngineCommand command : commands) {
            List<EngineEvent> events = new ArrayList<>();
            book.process(command, events);
            for (EngineEvent event : events) {
                switch (event) {
                    case EngineEvent.Accepted a -> accepted += a.quantityLots();
                    case EngineEvent.Trade t -> tradedTwice += 2 * t.quantityLots();
                    case EngineEvent.Cancelled c -> cancelled += c.remainingLots();
                    default -> { }
                }
            }
        }
        long resting = book.depth(Side.BUY, 100).stream().mapToLong(EngineEvent.LevelChange::totalLots).sum()
                + book.depth(Side.SELL, 100).stream().mapToLong(EngineEvent.LevelChange::totalLots).sum();

        // every accepted lot ends up traded (a trade consumes a lot from both sides), cancelled or resting
        assertThat(accepted).isEqualTo(tradedTwice + cancelled + resting);
    }

    @Property
    void restoringASnapshotMidStreamChangesNothing(
            @ForAll("commands") List<EngineCommand> commands,
            @ForAll("splitPoints") double splitFraction) {
        int split = (int) (commands.size() * splitFraction);
        OrderBook uninterrupted = new OrderBook(SYMBOL, 10_000);
        OrderBook beforeCrash = new OrderBook(SYMBOL, 10_000);
        List<EngineEvent> expectedTail = new ArrayList<>();

        for (int i = 0; i < commands.size(); i++) {
            List<EngineEvent> events = new ArrayList<>();
            uninterrupted.process(commands.get(i), events);
            if (i < split) {
                beforeCrash.process(commands.get(i), new ArrayList<>());
            } else {
                expectedTail.addAll(events);
            }
        }

        OrderBook recovered = OrderBook.restore(beforeCrash.state(), 10_000);
        List<EngineEvent> actualTail = new ArrayList<>();
        for (int i = split; i < commands.size(); i++) {
            recovered.process(commands.get(i), actualTail);
        }

        assertThat(actualTail).isEqualTo(expectedTail);
        assertThat(recovered.state()).isEqualTo(uninterrupted.state());
    }

    @Provide
    Arbitrary<Double> splitPoints() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }
}
