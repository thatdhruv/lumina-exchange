package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.config.LuminaProperties;
import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.instrument.Instrument;
import com.lumina.orderbook.instrument.Instruments;
import com.lumina.orderbook.projection.ProjectionRepository;
import com.lumina.orderbook.view.BookView;
import com.lumina.orderbook.wire.EventBatch;
import com.lumina.orderbook.wire.WireCodec;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-sent events for live market data.
 *
 * <p>Each subscriber gets a full {@code snapshot} (from the read model) on connect and then
 * {@code book} and {@code trade} events as they happen. Level updates carry absolute quantities and
 * the stream sequence, so the client can drop anything older than its snapshot. The snapshot is
 * re-sent periodically, which also heals any update lost to projection lag.
 *
 * <p>A slow client must never stall the Kafka consumer thread, so every subscriber drains its own
 * bounded queue on a virtual thread and is dropped if it falls behind.
 */
@Component
@ConditionalOnRole("api")
public class MarketDataHub {

    public record SnapshotMessage(String symbol, long sequence, List<LevelMessage> bids, List<LevelMessage> asks) {
    }

    public record LevelMessage(BigDecimal price, BigDecimal quantity) {
    }

    public record BookMessage(String symbol, long sequence, List<LevelUpdate> levels) {
    }

    public record LevelUpdate(String side, BigDecimal price, BigDecimal quantity) {
    }

    public record TradeMessage(UUID id, String symbol, long sequence, BigDecimal price, BigDecimal quantity,
                               String takerSide, Instant executedAt) {
    }

    private record Message(String name, Object data) {
    }

    private static final int QUEUE_CAPACITY = 1_000;
    private static final int SNAPSHOT_DEPTH = 20;

    private static final Logger log = LoggerFactory.getLogger(MarketDataHub.class);

    private final Instruments instruments;
    private final ProjectionRepository projection;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("market-data-resend").daemon().factory());
    private final AtomicInteger connected = new AtomicInteger();

    public MarketDataHub(Instruments instruments, ProjectionRepository projection,
                         LuminaProperties props, MeterRegistry meters) {
        this.instruments = instruments;
        this.projection = projection;
        meters.gauge("lumina.gateway.stream.subscribers", connected);
        long resendMs = props.gateway().bookResendInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::resendSnapshots, resendMs, resendMs, TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe(String symbol) {
        instruments.require(symbol);
        Subscriber subscriber = new Subscriber(symbol);
        subscribers.computeIfAbsent(symbol, s -> new CopyOnWriteArrayList<>()).add(subscriber);
        connected.incrementAndGet();
        subscriber.offer(new Message("snapshot", snapshot(symbol)));
        return subscriber.emitter;
    }

    void publish(EventBatch batch) {
        CopyOnWriteArrayList<Subscriber> targets = subscribers.get(batch.getSymbol());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        Instrument instrument = instruments.all().stream()
                .filter(i -> i.symbol().equals(batch.getSymbol())).findFirst().orElse(null);
        if (instrument == null) {
            return;
        }
        for (EngineEvent event : WireCodec.events(batch)) {
            Message message = switch (event) {
                case EngineEvent.Trade t -> new Message("trade", new TradeMessage(t.tradeId(), batch.getSymbol(),
                        t.seq(), instrument.price(t.priceTicks()), instrument.quantity(t.quantityLots()),
                        t.takerSide().name(), Instant.ofEpochMilli(t.timestampMs())));
                case EngineEvent.BookChanged b -> new Message("book", new BookMessage(batch.getSymbol(), b.seq(),
                        b.levels().stream().map(l -> new LevelUpdate(l.side() == Side.BUY ? "BUY" : "SELL",
                                instrument.price(l.priceTicks()), instrument.quantity(l.totalLots()))).toList()));
                default -> null;
            };
            if (message != null) {
                for (Subscriber s : targets) {
                    s.offer(message);
                }
            }
        }
    }

    private SnapshotMessage snapshot(String symbol) {
        BookView book = projection.orderBook(symbol, SNAPSHOT_DEPTH);
        return new SnapshotMessage(symbol, book.sequence(),
                book.bids().stream().map(l -> new LevelMessage(l.price(), l.quantity())).toList(),
                book.asks().stream().map(l -> new LevelMessage(l.price(), l.quantity())).toList());
    }

    private void resendSnapshots() {
        try {
            subscribers.forEach((symbol, list) -> {
                if (!list.isEmpty()) {
                    Message message = new Message("snapshot", snapshot(symbol));
                    list.forEach(s -> s.offer(message));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Snapshot resend failed", e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        subscribers.values().forEach(list -> list.forEach(Subscriber::close));
    }

    private final class Subscriber {
        final String symbol;
        final SseEmitter emitter = new SseEmitter(0L);
        final LinkedBlockingQueue<Message> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        volatile boolean open = true;

        Subscriber(String symbol) {
            this.symbol = symbol;
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(e -> close());
            Thread.startVirtualThread(this::drain);
        }

        void offer(Message message) {
            if (open && !queue.offer(message)) {
                log.info("Dropping slow market-data subscriber on {}", symbol);
                close();
                emitter.complete();
            }
        }

        private void drain() {
            try {
                while (open) {
                    Message message = queue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        emitter.send(SseEmitter.event().name(message.name()).data(message.data(), MediaType.APPLICATION_JSON));
                    }
                }
            } catch (Exception e) {
                close();
            }
        }

        void close() {
            if (open) {
                open = false;
                connected.decrementAndGet();
                CopyOnWriteArrayList<Subscriber> list = subscribers.get(symbol);
                if (list != null) {
                    list.remove(this);
                }
            }
        }
    }
}
