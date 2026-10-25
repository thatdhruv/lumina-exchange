package com.lumina.orderbook.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Price-time priority limit order book for one symbol. Single-threaded by contract: the owner of
 * the partition feeds it commands in log order, which is what makes the engine deterministic.
 *
 * <p>Resting orders sit in per-price FIFO queues built as intrusive doubly linked lists, so cancel
 * is O(1) after the id lookup and insert is O(log levels). Prices and quantities are scaled
 * {@code long}s (ticks and lots); no {@code BigDecimal} is allocated on the hot path.
 */
public final class OrderBook {

    private final String symbol;
    private final int recentIdCapacity;
    private final TreeMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Level> asks = new TreeMap<>();
    private final Map<UUID, Node> live = new HashMap<>();
    private final LinkedHashSet<UUID> recent = new LinkedHashSet<>();

    private long seq;
    private long nextArrival;

    public OrderBook(String symbol, int recentIdCapacity) {
        this.symbol = symbol;
        this.recentIdCapacity = recentIdCapacity;
    }

    public static OrderBook restore(BookState state, int recentIdCapacity) {
        OrderBook book = new OrderBook(state.symbol(), recentIdCapacity);
        book.seq = state.lastSeq();
        book.nextArrival = state.nextArrival();
        state.orders().stream()
                .sorted(Comparator.comparingLong(BookState.Resting::arrival))
                .forEach(r -> book.rest(new Node(
                        r.orderId(), r.accountId(), r.side(), r.priceTicks(), r.remainingLots(), r.arrival())));
        book.recent.addAll(state.recentOrderIds());
        return book;
    }

    public BookState state() {
        List<BookState.Resting> orders = new ArrayList<>(live.size());
        for (Node n : live.values()) {
            orders.add(new BookState.Resting(n.id, n.account, n.side, n.price, n.remaining, n.arrival));
        }
        orders.sort(Comparator.comparingLong(BookState.Resting::arrival));
        return new BookState(symbol, seq, nextArrival, orders, List.copyOf(recent));
    }

    public String symbol() {
        return symbol;
    }

    public long lastSeq() {
        return seq;
    }

    public int liveOrderCount() {
        return live.size();
    }

    public long bestBid() {
        return bids.isEmpty() ? 0 : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? 0 : asks.firstKey();
    }

    /** Top {@code depth} aggregated levels of one side, best first. */
    public List<EngineEvent.LevelChange> depth(Side side, int depth) {
        List<EngineEvent.LevelChange> out = new ArrayList<>();
        for (Level level : (side == Side.BUY ? bids : asks).values()) {
            if (out.size() == depth) {
                break;
            }
            out.add(new EngineEvent.LevelChange(side, level.price, level.total));
        }
        return out;
    }

    public void process(EngineCommand command, List<EngineEvent> out) {
        switch (command) {
            case EngineCommand.Place p -> place(p, out);
            case EngineCommand.Cancel c -> cancel(c, out);
        }
    }

    // ---- place -------------------------------------------------------------------------------

    private void place(EngineCommand.Place cmd, List<EngineEvent> out) {
        long ts = cmd.timestampMs();
        if (live.containsKey(cmd.orderId()) || recent.contains(cmd.orderId())) {
            out.add(new EngineEvent.Rejected(++seq, ts, cmd.orderId(), cmd.accountId(), RejectReason.DUPLICATE_ORDER_ID));
            return;
        }

        TimeInForce tif = cmd.type() == OrderType.MARKET && cmd.timeInForce() == TimeInForce.GTC
                ? TimeInForce.IOC
                : cmd.timeInForce();

        RejectReason invalid = validate(cmd, tif);
        if (invalid != null) {
            reject(cmd, invalid, ts, out);
            return;
        }

        boolean market = cmd.type() == OrderType.MARKET;
        long limit = cmd.priceTicks();

        if (cmd.postOnly() && crossesBest(cmd.side(), limit)) {
            reject(cmd, RejectReason.POST_ONLY_WOULD_CROSS, ts, out);
            return;
        }
        if (tif == TimeInForce.FOK && !fillable(cmd, market)) {
            reject(cmd, RejectReason.FOK_UNFILLABLE, ts, out);
            return;
        }

        out.add(new EngineEvent.Accepted(++seq, ts, cmd.orderId(), cmd.accountId(), cmd.side(), cmd.type(),
                tif, cmd.postOnly(), market ? 0 : limit, cmd.quantityLots()));

        TreeSet<Long> touchedBids = new TreeSet<>();
        TreeSet<Long> touchedAsks = new TreeSet<>();
        TreeMap<Long, Level> opposite = cmd.side() == Side.BUY ? asks : bids;
        TreeSet<Long> touchedOpposite = cmd.side() == Side.BUY ? touchedAsks : touchedBids;

        long remaining = cmd.quantityLots();
        boolean selfTrade = false;

        matching:
        while (remaining > 0 && !opposite.isEmpty()) {
            Level level = opposite.firstEntry().getValue();
            if (!market && !crosses(cmd.side(), limit, level.price)) {
                break;
            }
            while (remaining > 0 && level.head != null) {
                Node maker = level.head;
                if (maker.account.equals(cmd.accountId())) {
                    selfTrade = true;
                    break matching;
                }
                long fill = Math.min(remaining, maker.remaining);
                remaining -= fill;
                maker.remaining -= fill;
                level.total -= fill;
                touchedOpposite.add(level.price);

                boolean buyerIsTaker = cmd.side() == Side.BUY;
                long tradeSeq = ++seq;
                out.add(new EngineEvent.Trade(tradeSeq, ts, tradeId(tradeSeq), cmd.orderId(), maker.id,
                        buyerIsTaker ? cmd.orderId() : maker.id,
                        buyerIsTaker ? maker.id : cmd.orderId(),
                        cmd.side(), level.price, fill));

                if (maker.remaining == 0) {
                    unlink(maker);
                    live.remove(maker.id);
                    remember(maker.id);
                }
            }
            if (level.head == null) {
                opposite.remove(level.price);
            }
        }

        if (remaining > 0) {
            if (!selfTrade && tif == TimeInForce.GTC) {
                Node node = new Node(cmd.orderId(), cmd.accountId(), cmd.side(), limit, remaining, nextArrival++);
                rest(node);
                (cmd.side() == Side.BUY ? touchedBids : touchedAsks).add(limit);
            } else {
                remember(cmd.orderId());
                out.add(new EngineEvent.Cancelled(++seq, ts, cmd.orderId(), remaining,
                        selfTrade ? CancelReason.SELF_TRADE_PREVENTED : CancelReason.UNFILLED_REMAINDER));
            }
        } else {
            remember(cmd.orderId());
        }

        emitBookChange(touchedBids, touchedAsks, ts, out);
    }

    private RejectReason validate(EngineCommand.Place cmd, TimeInForce tif) {
        if (cmd.quantityLots() <= 0 || cmd.accountId() == null || cmd.accountId().isEmpty()) {
            return RejectReason.INVALID_ORDER;
        }
        if (cmd.type() == OrderType.LIMIT && cmd.priceTicks() <= 0) {
            return RejectReason.INVALID_ORDER;
        }
        if (cmd.postOnly() && (cmd.type() != OrderType.LIMIT || tif != TimeInForce.GTC)) {
            return RejectReason.INVALID_ORDER;
        }
        return null;
    }

    private void reject(EngineCommand.Place cmd, RejectReason reason, long ts, List<EngineEvent> out) {
        remember(cmd.orderId());
        out.add(new EngineEvent.Rejected(++seq, ts, cmd.orderId(), cmd.accountId(), reason));
    }

    private boolean crossesBest(Side side, long limit) {
        TreeMap<Long, Level> opposite = side == Side.BUY ? asks : bids;
        return !opposite.isEmpty() && crosses(side, limit, opposite.firstKey());
    }

    private static boolean crosses(Side taker, long limit, long levelPrice) {
        return taker == Side.BUY ? levelPrice <= limit : levelPrice >= limit;
    }

    /** Can the whole order fill right now, honouring self-trade prevention? */
    private boolean fillable(EngineCommand.Place cmd, boolean market) {
        TreeMap<Long, Level> opposite = cmd.side() == Side.BUY ? asks : bids;
        long available = 0;
        for (Level level : opposite.values()) {
            if (!market && !crosses(cmd.side(), cmd.priceTicks(), level.price)) {
                return false;
            }
            for (Node n = level.head; n != null; n = n.next) {
                if (n.account.equals(cmd.accountId())) {
                    return false;
                }
                available += n.remaining;
                if (available >= cmd.quantityLots()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- cancel ------------------------------------------------------------------------------

    private void cancel(EngineCommand.Cancel cmd, List<EngineEvent> out) {
        long ts = cmd.timestampMs();
        Node node = live.get(cmd.orderId());
        if (node == null) {
            out.add(new EngineEvent.Rejected(++seq, ts, cmd.orderId(), cmd.accountId(), RejectReason.UNKNOWN_ORDER));
            return;
        }
        if (!node.account.equals(cmd.accountId())) {
            out.add(new EngineEvent.Rejected(++seq, ts, cmd.orderId(), cmd.accountId(), RejectReason.NOT_ORDER_OWNER));
            return;
        }
        Level level = node.level;
        long price = node.price;
        Side side = node.side;
        unlink(node);
        live.remove(node.id);
        remember(node.id);
        if (level.head == null) {
            (side == Side.BUY ? bids : asks).remove(price);
        }
        out.add(new EngineEvent.Cancelled(++seq, ts, node.id, node.remaining, CancelReason.USER));

        TreeSet<Long> touchedBids = new TreeSet<>();
        TreeSet<Long> touchedAsks = new TreeSet<>();
        (side == Side.BUY ? touchedBids : touchedAsks).add(price);
        emitBookChange(touchedBids, touchedAsks, ts, out);
    }

    // ---- book maintenance --------------------------------------------------------------------

    private void rest(Node node) {
        TreeMap<Long, Level> side = node.side == Side.BUY ? bids : asks;
        Level level = side.computeIfAbsent(node.price, Level::new);
        node.level = level;
        node.prev = level.tail;
        if (level.tail == null) {
            level.head = node;
        } else {
            level.tail.next = node;
        }
        level.tail = node;
        level.total += node.remaining;
        live.put(node.id, node);
    }

    /** Detach from the level's list. The caller removes an emptied level from the price map. */
    private void unlink(Node node) {
        Level level = node.level;
        if (node.prev == null) {
            level.head = node.next;
        } else {
            node.prev.next = node.next;
        }
        if (node.next == null) {
            level.tail = node.prev;
        } else {
            node.next.prev = node.prev;
        }
        level.total -= node.remaining;
        node.prev = null;
        node.next = null;
    }

    private void remember(UUID orderId) {
        recent.add(orderId);
        if (recent.size() > recentIdCapacity) {
            Iterator<UUID> it = recent.iterator();
            it.next();
            it.remove();
        }
    }

    private void emitBookChange(TreeSet<Long> touchedBids, TreeSet<Long> touchedAsks, long ts, List<EngineEvent> out) {
        if (touchedBids.isEmpty() && touchedAsks.isEmpty()) {
            return;
        }
        List<EngineEvent.LevelChange> changes = new ArrayList<>(touchedBids.size() + touchedAsks.size());
        for (long price : touchedBids.descendingSet()) {
            Level level = bids.get(price);
            changes.add(new EngineEvent.LevelChange(Side.BUY, price, level == null ? 0 : level.total));
        }
        for (long price : touchedAsks) {
            Level level = asks.get(price);
            changes.add(new EngineEvent.LevelChange(Side.SELL, price, level == null ? 0 : level.total));
        }
        out.add(new EngineEvent.BookChanged(++seq, ts, List.copyOf(changes)));
    }

    private UUID tradeId(long tradeSeq) {
        return UUID.nameUUIDFromBytes((symbol + ":" + tradeSeq).getBytes(StandardCharsets.UTF_8));
    }

    // ---- internals ---------------------------------------------------------------------------

    private static final class Level {
        final long price;
        long total;
        Node head;
        Node tail;

        Level(long price) {
            this.price = price;
        }
    }

    private static final class Node {
        final UUID id;
        final String account;
        final Side side;
        final long price;
        final long arrival;
        long remaining;
        Level level;
        Node prev;
        Node next;

        Node(UUID id, String account, Side side, long price, long remaining, long arrival) {
            this.id = id;
            this.account = account;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
            this.arrival = arrival;
        }
    }
}
