package com.lumina.orderbook.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.lumina.orderbook.engine.BookState;
import com.lumina.orderbook.engine.CancelReason;
import com.lumina.orderbook.engine.EngineCommand;
import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.RejectReason;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Maps between the pure engine types and the protobuf wire contract. */
public final class WireCodec {

    private WireCodec() {
    }

    public record DecodedCommand(String commandId, EngineCommand command) {
    }

    public record DecodedSnapshot(int partition, long kafkaOffset, List<BookState> books) {
    }

    // ---- commands ----------------------------------------------------------------------------

    public static byte[] encode(String commandId, EngineCommand command) {
        Command.Builder b = Command.newBuilder().setCommandId(commandId).setTimestampMs(command.timestampMs());
        switch (command) {
            case EngineCommand.Place p -> b.setPlace(PlaceOrder.newBuilder()
                    .setOrderId(p.orderId().toString())
                    .setAccountId(p.accountId())
                    .setSymbol(p.symbol())
                    .setSide(wire(p.side()))
                    .setType(wire(p.type()))
                    .setTimeInForce(wire(p.timeInForce()))
                    .setPostOnly(p.postOnly())
                    .setPriceTicks(p.priceTicks())
                    .setQuantityLots(p.quantityLots()));
            case EngineCommand.Cancel c -> b.setCancel(CancelOrder.newBuilder()
                    .setOrderId(c.orderId().toString())
                    .setAccountId(c.accountId())
                    .setSymbol(c.symbol()));
        }
        return b.build().toByteArray();
    }

    public static DecodedCommand decodeCommand(byte[] bytes) throws InvalidProtocolBufferException {
        Command c = Command.parseFrom(bytes);
        long ts = c.getTimestampMs();
        EngineCommand command = switch (c.getBodyCase()) {
            case PLACE -> {
                PlaceOrder p = c.getPlace();
                yield new EngineCommand.Place(p.getSymbol(), UUID.fromString(p.getOrderId()), p.getAccountId(),
                        engine(p.getSide()), engine(p.getType()), engine(p.getTimeInForce()), p.getPostOnly(),
                        p.getPriceTicks(), p.getQuantityLots(), ts);
            }
            case CANCEL -> {
                CancelOrder x = c.getCancel();
                yield new EngineCommand.Cancel(x.getSymbol(), UUID.fromString(x.getOrderId()), x.getAccountId(), ts);
            }
            case BODY_NOT_SET -> throw new InvalidProtocolBufferException("command has no body");
        };
        return new DecodedCommand(c.getCommandId(), command);
    }

    // ---- events ------------------------------------------------------------------------------

    public static EventBatch toBatch(String commandId, String symbol, List<EngineEvent> events) {
        EventBatch.Builder batch = EventBatch.newBuilder().setCommandId(commandId).setSymbol(symbol);
        for (EngineEvent e : events) {
            batch.addEvents(toWire(e));
        }
        return batch.build();
    }

    public static Event toWire(EngineEvent e) {
        Event.Builder b = Event.newBuilder().setSeq(e.seq()).setTimestampMs(e.timestampMs());
        switch (e) {
            case EngineEvent.Accepted a -> b.setAccepted(OrderAccepted.newBuilder()
                    .setOrderId(a.orderId().toString())
                    .setAccountId(a.accountId())
                    .setSide(wire(a.side()))
                    .setType(wire(a.type()))
                    .setTimeInForce(wire(a.timeInForce()))
                    .setPostOnly(a.postOnly())
                    .setPriceTicks(a.priceTicks())
                    .setQuantityLots(a.quantityLots()));
            case EngineEvent.Rejected r -> b.setRejected(OrderRejected.newBuilder()
                    .setOrderId(r.orderId().toString())
                    .setAccountId(r.accountId())
                    .setReason(wire(r.reason())));
            case EngineEvent.Trade t -> b.setTrade(TradeExecuted.newBuilder()
                    .setTradeId(t.tradeId().toString())
                    .setTakerOrderId(t.takerOrderId().toString())
                    .setMakerOrderId(t.makerOrderId().toString())
                    .setBuyOrderId(t.buyOrderId().toString())
                    .setSellOrderId(t.sellOrderId().toString())
                    .setTakerSide(wire(t.takerSide()))
                    .setPriceTicks(t.priceTicks())
                    .setQuantityLots(t.quantityLots()));
            case EngineEvent.Cancelled c -> b.setCancelled(OrderCancelled.newBuilder()
                    .setOrderId(c.orderId().toString())
                    .setRemainingLots(c.remainingLots())
                    .setReason(wire(c.reason())));
            case EngineEvent.BookChanged bc -> {
                BookUpdate.Builder u = BookUpdate.newBuilder();
                for (EngineEvent.LevelChange l : bc.levels()) {
                    u.addLevels(LevelChange.newBuilder()
                            .setSide(wire(l.side()))
                            .setPriceTicks(l.priceTicks())
                            .setTotalLots(l.totalLots()));
                }
                b.setBook(u);
            }
        }
        return b.build();
    }

    public static EngineEvent fromWire(Event e) {
        long seq = e.getSeq();
        long ts = e.getTimestampMs();
        return switch (e.getBodyCase()) {
            case ACCEPTED -> {
                OrderAccepted a = e.getAccepted();
                yield new EngineEvent.Accepted(seq, ts, UUID.fromString(a.getOrderId()), a.getAccountId(),
                        engine(a.getSide()), engine(a.getType()), engine(a.getTimeInForce()), a.getPostOnly(),
                        a.getPriceTicks(), a.getQuantityLots());
            }
            case REJECTED -> {
                OrderRejected r = e.getRejected();
                yield new EngineEvent.Rejected(seq, ts, UUID.fromString(r.getOrderId()), r.getAccountId(),
                        engine(r.getReason()));
            }
            case TRADE -> {
                TradeExecuted t = e.getTrade();
                yield new EngineEvent.Trade(seq, ts, UUID.fromString(t.getTradeId()),
                        UUID.fromString(t.getTakerOrderId()), UUID.fromString(t.getMakerOrderId()),
                        UUID.fromString(t.getBuyOrderId()), UUID.fromString(t.getSellOrderId()),
                        engine(t.getTakerSide()), t.getPriceTicks(), t.getQuantityLots());
            }
            case CANCELLED -> {
                OrderCancelled c = e.getCancelled();
                yield new EngineEvent.Cancelled(seq, ts, UUID.fromString(c.getOrderId()), c.getRemainingLots(),
                        engine(c.getReason()));
            }
            case BOOK -> {
                List<EngineEvent.LevelChange> levels = new ArrayList<>();
                for (LevelChange l : e.getBook().getLevelsList()) {
                    levels.add(new EngineEvent.LevelChange(engine(l.getSide()), l.getPriceTicks(), l.getTotalLots()));
                }
                yield new EngineEvent.BookChanged(seq, ts, levels);
            }
            case BODY_NOT_SET -> throw new IllegalArgumentException("event " + seq + " has no body");
        };
    }

    public static List<EngineEvent> events(EventBatch batch) {
        List<EngineEvent> out = new ArrayList<>(batch.getEventsCount());
        for (Event e : batch.getEventsList()) {
            out.add(fromWire(e));
        }
        return out;
    }

    // ---- snapshots ---------------------------------------------------------------------------

    public static byte[] encodeSnapshot(int partition, long kafkaOffset, long takenAtMs, List<BookState> books) {
        EngineSnapshot.Builder snapshot = EngineSnapshot.newBuilder()
                .setPartition(partition)
                .setKafkaOffset(kafkaOffset)
                .setTakenAtMs(takenAtMs);
        for (BookState state : books) {
            BookSnapshot.Builder b = BookSnapshot.newBuilder()
                    .setSymbol(state.symbol())
                    .setLastSeq(state.lastSeq())
                    .setNextArrival(state.nextArrival());
            for (BookState.Resting r : state.orders()) {
                b.addOrders(RestingOrder.newBuilder()
                        .setOrderId(r.orderId().toString())
                        .setAccountId(r.accountId())
                        .setSide(wire(r.side()))
                        .setPriceTicks(r.priceTicks())
                        .setRemainingLots(r.remainingLots())
                        .setArrival(r.arrival()));
            }
            for (UUID id : state.recentOrderIds()) {
                b.addRecentOrderIds(id.toString());
            }
            snapshot.addBooks(b);
        }
        return snapshot.build().toByteArray();
    }

    public static DecodedSnapshot decodeSnapshot(byte[] bytes) throws InvalidProtocolBufferException {
        EngineSnapshot s = EngineSnapshot.parseFrom(bytes);
        List<BookState> books = new ArrayList<>(s.getBooksCount());
        for (BookSnapshot b : s.getBooksList()) {
            List<BookState.Resting> orders = new ArrayList<>(b.getOrdersCount());
            for (RestingOrder r : b.getOrdersList()) {
                orders.add(new BookState.Resting(UUID.fromString(r.getOrderId()), r.getAccountId(),
                        engine(r.getSide()), r.getPriceTicks(), r.getRemainingLots(), r.getArrival()));
            }
            List<UUID> recent = new ArrayList<>(b.getRecentOrderIdsCount());
            for (String id : b.getRecentOrderIdsList()) {
                recent.add(UUID.fromString(id));
            }
            books.add(new BookState(b.getSymbol(), b.getLastSeq(), b.getNextArrival(), orders, recent));
        }
        return new DecodedSnapshot(s.getPartition(), s.getKafkaOffset(), books);
    }

    // ---- enums -------------------------------------------------------------------------------

    private static com.lumina.orderbook.wire.Side wire(Side side) {
        return side == Side.BUY ? com.lumina.orderbook.wire.Side.SIDE_BUY : com.lumina.orderbook.wire.Side.SIDE_SELL;
    }

    private static Side engine(com.lumina.orderbook.wire.Side side) {
        return switch (side) {
            case SIDE_BUY -> Side.BUY;
            case SIDE_SELL -> Side.SELL;
            default -> throw new IllegalArgumentException("side is required");
        };
    }

    private static com.lumina.orderbook.wire.OrderType wire(OrderType type) {
        return type == OrderType.LIMIT
                ? com.lumina.orderbook.wire.OrderType.ORDER_TYPE_LIMIT
                : com.lumina.orderbook.wire.OrderType.ORDER_TYPE_MARKET;
    }

    private static OrderType engine(com.lumina.orderbook.wire.OrderType type) {
        return switch (type) {
            case ORDER_TYPE_LIMIT -> OrderType.LIMIT;
            case ORDER_TYPE_MARKET -> OrderType.MARKET;
            default -> throw new IllegalArgumentException("order type is required");
        };
    }

    private static com.lumina.orderbook.wire.TimeInForce wire(TimeInForce tif) {
        return switch (tif) {
            case GTC -> com.lumina.orderbook.wire.TimeInForce.TIME_IN_FORCE_GTC;
            case IOC -> com.lumina.orderbook.wire.TimeInForce.TIME_IN_FORCE_IOC;
            case FOK -> com.lumina.orderbook.wire.TimeInForce.TIME_IN_FORCE_FOK;
        };
    }

    private static TimeInForce engine(com.lumina.orderbook.wire.TimeInForce tif) {
        return switch (tif) {
            case TIME_IN_FORCE_IOC -> TimeInForce.IOC;
            case TIME_IN_FORCE_FOK -> TimeInForce.FOK;
            default -> TimeInForce.GTC;
        };
    }

    private static com.lumina.orderbook.wire.RejectReason wire(RejectReason reason) {
        return switch (reason) {
            case DUPLICATE_ORDER_ID -> com.lumina.orderbook.wire.RejectReason.REJECT_REASON_DUPLICATE_ORDER_ID;
            case INVALID_ORDER -> com.lumina.orderbook.wire.RejectReason.REJECT_REASON_INVALID_ORDER;
            case POST_ONLY_WOULD_CROSS -> com.lumina.orderbook.wire.RejectReason.REJECT_REASON_POST_ONLY_WOULD_CROSS;
            case FOK_UNFILLABLE -> com.lumina.orderbook.wire.RejectReason.REJECT_REASON_FOK_UNFILLABLE;
            case UNKNOWN_ORDER -> com.lumina.orderbook.wire.RejectReason.REJECT_REASON_UNKNOWN_ORDER;
            case NOT_ORDER_OWNER -> com.lumina.orderbook.wire.RejectReason.REJECT_REASON_NOT_ORDER_OWNER;
        };
    }

    private static RejectReason engine(com.lumina.orderbook.wire.RejectReason reason) {
        return switch (reason) {
            case REJECT_REASON_DUPLICATE_ORDER_ID -> RejectReason.DUPLICATE_ORDER_ID;
            case REJECT_REASON_POST_ONLY_WOULD_CROSS -> RejectReason.POST_ONLY_WOULD_CROSS;
            case REJECT_REASON_FOK_UNFILLABLE -> RejectReason.FOK_UNFILLABLE;
            case REJECT_REASON_UNKNOWN_ORDER -> RejectReason.UNKNOWN_ORDER;
            case REJECT_REASON_NOT_ORDER_OWNER -> RejectReason.NOT_ORDER_OWNER;
            default -> RejectReason.INVALID_ORDER;
        };
    }

    private static com.lumina.orderbook.wire.CancelReason wire(CancelReason reason) {
        return switch (reason) {
            case USER -> com.lumina.orderbook.wire.CancelReason.CANCEL_REASON_USER;
            case UNFILLED_REMAINDER -> com.lumina.orderbook.wire.CancelReason.CANCEL_REASON_UNFILLED_REMAINDER;
            case SELF_TRADE_PREVENTED -> com.lumina.orderbook.wire.CancelReason.CANCEL_REASON_SELF_TRADE_PREVENTED;
        };
    }

    private static CancelReason engine(com.lumina.orderbook.wire.CancelReason reason) {
        return switch (reason) {
            case CANCEL_REASON_USER -> CancelReason.USER;
            case CANCEL_REASON_SELF_TRADE_PREVENTED -> CancelReason.SELF_TRADE_PREVENTED;
            default -> CancelReason.UNFILLED_REMAINDER;
        };
    }
}
