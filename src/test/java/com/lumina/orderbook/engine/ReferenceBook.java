package com.lumina.orderbook.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deliberately naive order book (flat list, linear scans) used as an oracle for the real one.
 * Emits normalized event strings so it can be compared without sequence numbers.
 */
final class ReferenceBook {

    private static final class R {
        final UUID id;
        final String acct;
        final Side side;
        final long price;
        long rem;
        final long arrival;

        R(UUID id, String acct, Side side, long price, long rem, long arrival) {
            this.id = id;
            this.acct = acct;
            this.side = side;
            this.price = price;
            this.rem = rem;
            this.arrival = arrival;
        }
    }

    private final List<R> resting = new ArrayList<>();
    private final Set<UUID> seen = new HashSet<>();
    private long arrival;

    List<String> process(EngineCommand command) {
        List<String> out = new ArrayList<>();
        switch (command) {
            case EngineCommand.Place p -> place(p, out);
            case EngineCommand.Cancel c -> cancel(c, out);
        }
        return out;
    }

    /** Same normalization for the engine's events. */
    static List<String> normalize(List<EngineEvent> events) {
        List<String> out = new ArrayList<>();
        for (EngineEvent e : events) {
            switch (e) {
                case EngineEvent.Accepted a -> out.add("ACC " + a.orderId() + " " + a.timeInForce());
                case EngineEvent.Rejected r -> out.add("REJ " + r.orderId() + " " + r.reason());
                case EngineEvent.Trade t -> out.add("TRD " + t.takerOrderId() + " " + t.makerOrderId()
                        + " " + t.priceTicks() + " " + t.quantityLots());
                case EngineEvent.Cancelled c -> out.add("CAN " + c.orderId() + " " + c.remainingLots() + " " + c.reason());
                case EngineEvent.BookChanged b -> { }
            }
        }
        return out;
    }

    List<EngineEvent.LevelChange> depth(Side side) {
        List<EngineEvent.LevelChange> out = new ArrayList<>();
        for (R r : sorted(side)) {
            if (!out.isEmpty() && out.getLast().priceTicks() == r.price) {
                EngineEvent.LevelChange last = out.removeLast();
                out.add(new EngineEvent.LevelChange(side, r.price, last.totalLots() + r.rem));
            } else {
                out.add(new EngineEvent.LevelChange(side, r.price, r.rem));
            }
        }
        return out;
    }

    private List<R> sorted(Side side) {
        Comparator<R> byPrice = side == Side.BUY
                ? Comparator.comparingLong((R r) -> r.price).reversed()
                : Comparator.comparingLong((R r) -> r.price);
        return resting.stream()
                .filter(r -> r.side == side)
                .sorted(byPrice.thenComparingLong(r -> r.arrival))
                .toList();
    }

    private void place(EngineCommand.Place c, List<String> out) {
        if (seen.contains(c.orderId())) {
            out.add("REJ " + c.orderId() + " DUPLICATE_ORDER_ID");
            return;
        }
        seen.add(c.orderId());
        boolean market = c.type() == OrderType.MARKET;
        TimeInForce tif = market && c.timeInForce() == TimeInForce.GTC ? TimeInForce.IOC : c.timeInForce();

        if (c.quantityLots() <= 0 || c.accountId().isEmpty()
                || (!market && c.priceTicks() <= 0)
                || (c.postOnly() && (market || tif != TimeInForce.GTC))) {
            out.add("REJ " + c.orderId() + " INVALID_ORDER");
            return;
        }
        List<R> opposite = sorted(c.side().opposite());
        if (c.postOnly() && !opposite.isEmpty() && crosses(c, opposite.getFirst())) {
            out.add("REJ " + c.orderId() + " POST_ONLY_WOULD_CROSS");
            return;
        }
        if (tif == TimeInForce.FOK) {
            long available = 0;
            boolean ok = false;
            for (R r : opposite) {
                if (!crosses(c, r) || r.acct.equals(c.accountId())) {
                    break;
                }
                available += r.rem;
                if (available >= c.quantityLots()) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                out.add("REJ " + c.orderId() + " FOK_UNFILLABLE");
                return;
            }
        }

        out.add("ACC " + c.orderId() + " " + tif);
        long remaining = c.quantityLots();
        boolean selfTrade = false;
        while (remaining > 0) {
            List<R> book = sorted(c.side().opposite());
            if (book.isEmpty() || !crosses(c, book.getFirst())) {
                break;
            }
            R maker = book.getFirst();
            if (maker.acct.equals(c.accountId())) {
                selfTrade = true;
                break;
            }
            long fill = Math.min(remaining, maker.rem);
            remaining -= fill;
            maker.rem -= fill;
            out.add("TRD " + c.orderId() + " " + maker.id + " " + maker.price + " " + fill);
            if (maker.rem == 0) {
                resting.remove(maker);
            }
        }
        if (remaining > 0) {
            if (!selfTrade && tif == TimeInForce.GTC) {
                resting.add(new R(c.orderId(), c.accountId(), c.side(), c.priceTicks(), remaining, arrival++));
            } else {
                out.add("CAN " + c.orderId() + " " + remaining + " "
                        + (selfTrade ? CancelReason.SELF_TRADE_PREVENTED : CancelReason.UNFILLED_REMAINDER));
            }
        }
    }

    private void cancel(EngineCommand.Cancel c, List<String> out) {
        R target = resting.stream().filter(r -> r.id.equals(c.orderId())).findFirst().orElse(null);
        if (target == null) {
            out.add("REJ " + c.orderId() + " UNKNOWN_ORDER");
        } else if (!target.acct.equals(c.accountId())) {
            out.add("REJ " + c.orderId() + " NOT_ORDER_OWNER");
        } else {
            resting.remove(target);
            out.add("CAN " + c.orderId() + " " + target.rem + " " + CancelReason.USER);
        }
    }

    private static boolean crosses(EngineCommand.Place c, R resting) {
        if (c.type() == OrderType.MARKET) {
            return true;
        }
        return c.side() == Side.BUY ? resting.price <= c.priceTicks() : resting.price >= c.priceTicks();
    }
}
