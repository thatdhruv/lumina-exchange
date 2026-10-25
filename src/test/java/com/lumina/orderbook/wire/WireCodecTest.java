package com.lumina.orderbook.wire;

import com.lumina.orderbook.engine.EngineCommand;
import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.engine.MatchingEngine;
import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WireCodecTest {

    @Test
    void placeCommandRoundTrips() throws Exception {
        EngineCommand.Place place = new EngineCommand.Place("BTC-USD", UUID.randomUUID(), "acct", Side.SELL,
                OrderType.LIMIT, TimeInForce.FOK, false, 5_000_000, 12, 1_700_000_000_000L);

        WireCodec.DecodedCommand decoded = WireCodec.decodeCommand(WireCodec.encode("cmd-1", place));

        assertThat(decoded.commandId()).isEqualTo("cmd-1");
        assertThat(decoded.command()).isEqualTo(place);
    }

    @Test
    void cancelCommandRoundTrips() throws Exception {
        EngineCommand.Cancel cancel = new EngineCommand.Cancel("ETH-USD", UUID.randomUUID(), "acct", 42L);

        assertThat(WireCodec.decodeCommand(WireCodec.encode("c", cancel)).command()).isEqualTo(cancel);
    }

    @Test
    void emptyCommandIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> WireCodec.decodeCommand(Command.newBuilder().setCommandId("x").build().toByteArray()));
    }

    @Provide
    Arbitrary<List<EngineCommand>> commands() {
        Arbitrary<Integer> idx = Arbitraries.integers().between(0, 29);
        Arbitrary<EngineCommand> place = Combinators.combine(
                idx,
                Arbitraries.of("a", "b"),
                Arbitraries.of(Side.class),
                Arbitraries.of(OrderType.class),
                Arbitraries.of(TimeInForce.class),
                Arbitraries.longs().between(95, 105),
                Arbitraries.longs().between(1, 9)
        ).as((i, acct, side, type, tif, price, qty) -> (EngineCommand) new EngineCommand.Place(
                "S", new UUID(0, i), acct, side, type, tif, false, price, qty, 7L));
        Arbitrary<EngineCommand> cancel = Combinators.combine(idx, Arbitraries.of("a", "b"))
                .as((i, acct) -> (EngineCommand) new EngineCommand.Cancel("S", new UUID(0, i), acct, 7L));
        return Arbitraries.oneOf(place, cancel).list().ofMinSize(1).ofMaxSize(80);
    }

    @Property
    void everyEngineEventSurvivesTheWire(@ForAll("commands") List<EngineCommand> commands) {
        MatchingEngine engine = new MatchingEngine();
        for (EngineCommand command : commands) {
            List<EngineEvent> events = engine.process(command);
            assertThat(WireCodec.events(WireCodec.toBatch("c", "S", events))).isEqualTo(events);
        }
    }

    @Property
    void snapshotsSurviveTheWire(@ForAll("commands") List<EngineCommand> commands) throws Exception {
        MatchingEngine engine = new MatchingEngine();
        commands.forEach(engine::process);

        byte[] bytes = WireCodec.encodeSnapshot(3, 99L, 1L, engine.snapshot());
        WireCodec.DecodedSnapshot decoded = WireCodec.decodeSnapshot(bytes);

        assertThat(decoded.partition()).isEqualTo(3);
        assertThat(decoded.kafkaOffset()).isEqualTo(99L);
        assertThat(decoded.books()).isEqualTo(engine.snapshot());

        MatchingEngine restored = new MatchingEngine();
        restored.restore(decoded.books());
        List<EngineEvent> probeEvents = new ArrayList<>(restored.process(new EngineCommand.Place(
                "S", new UUID(1, 1), "z", Side.BUY, OrderType.MARKET, TimeInForce.IOC, false, 0, 5, 8L)));
        assertThat(probeEvents).isEqualTo(engine.process(new EngineCommand.Place(
                "S", new UUID(1, 1), "z", Side.BUY, OrderType.MARKET, TimeInForce.IOC, false, 0, 5, 8L)));
    }
}
