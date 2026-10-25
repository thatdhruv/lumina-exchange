package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.config.LuminaProperties;
import com.lumina.orderbook.engine.EngineCommand;
import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.RejectReason;
import com.lumina.orderbook.engine.TimeInForce;
import com.lumina.orderbook.instrument.Instrument;
import com.lumina.orderbook.instrument.Instruments;
import com.lumina.orderbook.projection.ProjectionRepository;
import com.lumina.orderbook.view.BookView;
import com.lumina.orderbook.view.OrderStatus;
import com.lumina.orderbook.view.OrderView;
import com.lumina.orderbook.view.TradeView;
import com.lumina.orderbook.wire.EventBatch;
import com.lumina.orderbook.wire.WireCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Front door for order flow. Validates, appends a command to the log, then waits for the engine's
 * answer on the event stream. Reads are served from the Postgres projection.
 */
@Service
@ConditionalOnRole("api")
public class OrderGateway {

    private final Instruments instruments;
    private final PendingCommands pending;
    private final RateLimiter rateLimiter;
    private final ProjectionRepository projection;
    private final KafkaTemplate<String, byte[]> kafka;
    private final LuminaProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public OrderGateway(Instruments instruments,
                        PendingCommands pending,
                        RateLimiter rateLimiter,
                        ProjectionRepository projection,
                        KafkaTemplate<String, byte[]> kafka,
                        LuminaProperties props,
                        Clock clock,
                        MeterRegistry meters) {
        this.instruments = instruments;
        this.pending = pending;
        this.rateLimiter = rateLimiter;
        this.projection = projection;
        this.kafka = kafka;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    public PlaceOutcome place(String account, PlaceRequest request, String idempotencyKey) {
        Timer.Sample sample = Timer.start(meters);
        String outcomeTag = "error";
        try {
            rateLimiter.check(account);
            Instrument instrument = instruments.require(request.symbol());
            TimeInForce tif = request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
            long priceTicks = validate(instrument, request, tif);
            long lots = instrument.toLots(request.quantity());

            UUID orderId = idempotencyKey == null || idempotencyKey.isBlank()
                    ? UUID.randomUUID()
                    : UUID.nameUUIDFromBytes((account + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
            EngineCommand.Place command = new EngineCommand.Place(instrument.symbol(), orderId, account,
                    request.side(), request.type(), tif, request.postOnly(), priceTicks, lots, clock.millis());

            List<EngineEvent> events = WireCodec.events(dispatch(command));
            PlaceOutcome outcome = interpret(instrument, orderId, events);
            outcomeTag = outcome.getClass().getSimpleName().toLowerCase();
            return outcome;
        } finally {
            sample.stop(Timer.builder("lumina.gateway.order").tag("op", "place").tag("outcome", outcomeTag)
                    .register(meters));
        }
    }

    public OrderView cancel(String account, UUID orderId) {
        Timer.Sample sample = Timer.start(meters);
        String outcomeTag = "error";
        try {
            rateLimiter.check(account);
            OrderView existing = find(account, orderId);
            if (!existing.status().isActive()) {
                outcomeTag = "noop";
                return existing;
            }
            Instrument instrument = instruments.require(existing.symbol());
            EngineCommand.Cancel command = new EngineCommand.Cancel(existing.symbol(), orderId, account, clock.millis());
            List<EngineEvent> events = WireCodec.events(dispatch(command));

            for (EngineEvent event : events) {
                if (event instanceof EngineEvent.Cancelled c) {
                    outcomeTag = "cancelled";
                    BigDecimal cancelled = instrument.quantity(c.remainingLots());
                    return new OrderView(existing.id(), existing.accountId(), existing.symbol(), existing.side(),
                            existing.type(), existing.timeInForce(), existing.postOnly(), existing.price(),
                            existing.quantity(), existing.quantity().subtract(cancelled), BigDecimal.ZERO,
                            OrderStatus.CANCELLED, null, existing.createdAt(), Instant.ofEpochMilli(c.timestampMs()));
                }
                if (event instanceof EngineEvent.Rejected r) {
                    outcomeTag = "rejected";
                    if (r.reason() == RejectReason.NOT_ORDER_OWNER) {
                        throw GatewayException.notFound("order " + orderId + " not found");
                    }
                    throw GatewayException.conflict("ORDER_NOT_ACTIVE", "order is no longer active");
                }
            }
            throw GatewayException.unavailable("engine returned no result for the cancel");
        } finally {
            sample.stop(Timer.builder("lumina.gateway.order").tag("op", "cancel").tag("outcome", outcomeTag)
                    .register(meters));
        }
    }

    public OrderView find(String account, UUID orderId) {
        return projection.findOrder(orderId)
                .filter(o -> o.accountId().equals(account))
                .orElseThrow(() -> GatewayException.notFound("order " + orderId + " not found"));
    }

    public List<OrderView> openOrders(String account, String symbol) {
        return projection.openOrders(account, symbol, 200);
    }

    public BookView orderBook(String symbol, int depth) {
        instruments.require(symbol);
        return projection.orderBook(symbol, Math.max(1, Math.min(depth, 100)));
    }

    public List<TradeView> recentTrades(String symbol, int limit) {
        instruments.require(symbol);
        return projection.recentTrades(symbol, Math.max(1, Math.min(limit, 500)));
    }

    // ---- internals ---------------------------------------------------------------------------

    private long validate(Instrument instrument, PlaceRequest request, TimeInForce tif) {
        if (request.type() == OrderType.LIMIT) {
            if (request.price() == null) {
                throw GatewayException.invalid("INVALID_PRICE", "limit orders require a price");
            }
            if (request.postOnly() && tif != TimeInForce.GTC) {
                throw GatewayException.invalid("INVALID_ORDER", "post-only orders must be GTC");
            }
            return instrument.toTicks(request.price());
        }
        if (request.price() != null) {
            throw GatewayException.invalid("INVALID_PRICE", "market orders do not take a price");
        }
        if (request.postOnly()) {
            throw GatewayException.invalid("INVALID_ORDER", "market orders cannot be post-only");
        }
        return 0;
    }

    private EventBatch dispatch(EngineCommand command) {
        String commandId = UUID.randomUUID().toString();
        var future = pending.register(commandId);
        try {
            kafka.send(props.kafka().commandsTopic(), command.symbol(), WireCodec.encode(commandId, command))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            pending.fail(commandId, error);
                        }
                    });
            return future.get(props.gateway().commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw GatewayException.timeout("the engine did not answer in time; the command may still be applied");
        } catch (ExecutionException e) {
            throw GatewayException.unavailable("could not reach the command log: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayException.unavailable("interrupted");
        } finally {
            pending.forget(commandId);
        }
    }

    private PlaceOutcome interpret(Instrument instrument, UUID orderId, List<EngineEvent> events) {
        EngineEvent.Accepted accepted = null;
        long filled = 0;
        EngineEvent.Cancelled cancelled = null;
        for (EngineEvent event : events) {
            switch (event) {
                case EngineEvent.Rejected r when r.orderId().equals(orderId) -> {
                    if (r.reason() == RejectReason.DUPLICATE_ORDER_ID) {
                        return new PlaceOutcome.Duplicate(orderId, projection.findOrder(orderId));
                    }
                    return new PlaceOutcome.Rejected(orderId, r.reason());
                }
                case EngineEvent.Accepted a when a.orderId().equals(orderId) -> accepted = a;
                case EngineEvent.Trade t when t.takerOrderId().equals(orderId) -> filled += t.quantityLots();
                case EngineEvent.Cancelled c when c.orderId().equals(orderId) -> cancelled = c;
                default -> { }
            }
        }
        if (accepted == null) {
            throw GatewayException.unavailable("engine returned no result for the order");
        }

        long working = accepted.quantityLots() - filled;
        OrderStatus status = cancelled != null ? OrderStatus.CANCELLED
                : working == 0 ? OrderStatus.FILLED
                : filled > 0 ? OrderStatus.PARTIAL
                : OrderStatus.OPEN;
        Instant at = Instant.ofEpochMilli(events.getLast().timestampMs());
        return new PlaceOutcome.Placed(new OrderView(
                orderId,
                accepted.accountId(),
                instrument.symbol(),
                accepted.side(),
                accepted.type(),
                accepted.timeInForce(),
                accepted.postOnly(),
                accepted.type() == OrderType.MARKET ? null : instrument.price(accepted.priceTicks()),
                instrument.quantity(accepted.quantityLots()),
                instrument.quantity(filled),
                status == OrderStatus.CANCELLED ? BigDecimal.ZERO : instrument.quantity(working),
                status,
                null,
                Instant.ofEpochMilli(accepted.timestampMs()),
                at));
    }
}
