package com.lumina.orderbook.projection;

import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.RejectReason;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;
import com.lumina.orderbook.instrument.Instrument;
import com.lumina.orderbook.instrument.Instruments;
import com.lumina.orderbook.view.BookView;
import com.lumina.orderbook.view.OrderStatus;
import com.lumina.orderbook.view.OrderView;
import com.lumina.orderbook.view.TradeView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres read model built from the event stream. Writes are idempotent per event through the
 * per-symbol {@code last_seq} cursor; callers must apply events in sequence order inside one
 * transaction.
 */
@Repository
public class ProjectionRepository {

    private final JdbcClient jdbc;
    private final Instruments instruments;

    public ProjectionRepository(JdbcClient jdbc, Instruments instruments) {
        this.jdbc = jdbc;
        this.instruments = instruments;
    }

    // ---- write side --------------------------------------------------------------------------

    public long cursor(String symbol) {
        return jdbc.sql("SELECT last_seq FROM projection_cursor WHERE symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public void saveCursor(String symbol, long seq) {
        jdbc.sql("""
                        INSERT INTO projection_cursor (symbol, last_seq, updated_at) VALUES (:s, :seq, now())
                        ON CONFLICT (symbol) DO UPDATE SET last_seq = EXCLUDED.last_seq, updated_at = now()
                        """)
                .param("s", symbol)
                .param("seq", seq)
                .update();
    }

    public void apply(String symbol, EngineEvent event) {
        Timestamp ts = Timestamp.from(Instant.ofEpochMilli(event.timestampMs()));
        switch (event) {
            case EngineEvent.Accepted a -> jdbc.sql("""
                            INSERT INTO orders (id, account_id, symbol, side, type, time_in_force, post_only,
                                                price_ticks, quantity_lots, remaining_lots, status, created_at, updated_at)
                            VALUES (:id, :acct, :sym, :side, :type, :tif, :post, :price, :qty, :qty, 'OPEN', :ts, :ts)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", a.orderId())
                    .param("acct", a.accountId())
                    .param("sym", symbol)
                    .param("side", a.side().name())
                    .param("type", a.type().name())
                    .param("tif", a.timeInForce().name())
                    .param("post", a.postOnly())
                    .param("price", a.type() == OrderType.MARKET ? null : a.priceTicks())
                    .param("qty", a.quantityLots())
                    .param("ts", ts)
                    .update();
            case EngineEvent.Rejected r -> {
                if (isPlaceRejection(r.reason())) {
                    jdbc.sql("""
                                    INSERT INTO orders (id, account_id, symbol, status, reject_reason, created_at, updated_at)
                                    VALUES (:id, :acct, :sym, 'REJECTED', :reason, :ts, :ts)
                                    ON CONFLICT (id) DO NOTHING
                                    """)
                            .param("id", r.orderId())
                            .param("acct", r.accountId())
                            .param("sym", symbol)
                            .param("reason", r.reason().name())
                            .param("ts", ts)
                            .update();
                }
            }
            case EngineEvent.Trade t -> {
                jdbc.sql("""
                                INSERT INTO trades (id, symbol, seq, price_ticks, quantity_lots, taker_side,
                                                    buy_order_id, sell_order_id, executed_at)
                                VALUES (:id, :sym, :seq, :price, :qty, :side, :buy, :sell, :ts)
                                ON CONFLICT (id) DO NOTHING
                                """)
                        .param("id", t.tradeId())
                        .param("sym", symbol)
                        .param("seq", t.seq())
                        .param("price", t.priceTicks())
                        .param("qty", t.quantityLots())
                        .param("side", t.takerSide().name())
                        .param("buy", t.buyOrderId())
                        .param("sell", t.sellOrderId())
                        .param("ts", ts)
                        .update();
                fill(t.buyOrderId(), t.quantityLots(), ts);
                fill(t.sellOrderId(), t.quantityLots(), ts);
            }
            case EngineEvent.Cancelled c -> jdbc.sql("""
                            UPDATE orders SET status = 'CANCELLED', remaining_lots = :rem, updated_at = :ts
                             WHERE id = :id
                            """)
                    .param("id", c.orderId())
                    .param("rem", c.remainingLots())
                    .param("ts", ts)
                    .update();
            case EngineEvent.BookChanged b -> {
                for (EngineEvent.LevelChange l : b.levels()) {
                    if (l.totalLots() == 0) {
                        jdbc.sql("DELETE FROM book_levels WHERE symbol = :s AND side = :side AND price_ticks = :p")
                                .param("s", symbol).param("side", l.side().name()).param("p", l.priceTicks())
                                .update();
                    } else {
                        jdbc.sql("""
                                        INSERT INTO book_levels (symbol, side, price_ticks, total_lots)
                                        VALUES (:s, :side, :p, :total)
                                        ON CONFLICT (symbol, side, price_ticks) DO UPDATE SET total_lots = EXCLUDED.total_lots
                                        """)
                                .param("s", symbol).param("side", l.side().name()).param("p", l.priceTicks())
                                .param("total", l.totalLots())
                                .update();
                    }
                }
            }
        }
    }

    private void fill(UUID orderId, long lots, Timestamp ts) {
        jdbc.sql("""
                        UPDATE orders
                           SET remaining_lots = remaining_lots - :q,
                               status = CASE WHEN remaining_lots - :q = 0 THEN 'FILLED' ELSE 'PARTIAL' END,
                               updated_at = :ts
                         WHERE id = :id
                        """)
                .param("q", lots)
                .param("ts", ts)
                .param("id", orderId)
                .update();
    }

    private static boolean isPlaceRejection(RejectReason reason) {
        return switch (reason) {
            case INVALID_ORDER, POST_ONLY_WOULD_CROSS, FOK_UNFILLABLE -> true;
            case DUPLICATE_ORDER_ID, UNKNOWN_ORDER, NOT_ORDER_OWNER -> false;
        };
    }

    // ---- read side ---------------------------------------------------------------------------

    public Optional<OrderView> findOrder(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id")
                .param("id", id)
                .query(this::mapOrder)
                .optional();
    }

    public List<OrderView> openOrders(String accountId, String symbol, int limit) {
        return jdbc.sql("""
                        SELECT * FROM orders
                         WHERE account_id = :acct AND status IN ('OPEN', 'PARTIAL')
                           AND (CAST(:sym AS TEXT) IS NULL OR symbol = :sym)
                         ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("acct", accountId)
                .param("sym", symbol)
                .param("limit", limit)
                .query(this::mapOrder)
                .list();
    }

    public List<TradeView> recentTrades(String symbol, int limit) {
        Instrument instrument = instruments.require(symbol);
        return jdbc.sql("SELECT * FROM trades WHERE symbol = :s ORDER BY seq DESC LIMIT :limit")
                .param("s", symbol)
                .param("limit", limit)
                .query((rs, i) -> new TradeView(
                        rs.getObject("id", UUID.class),
                        symbol,
                        rs.getLong("seq"),
                        instrument.price(rs.getLong("price_ticks")),
                        instrument.quantity(rs.getLong("quantity_lots")),
                        Side.valueOf(rs.getString("taker_side")),
                        rs.getObject("buy_order_id", UUID.class),
                        rs.getObject("sell_order_id", UUID.class),
                        rs.getTimestamp("executed_at").toInstant()))
                .list();
    }

    public BookView orderBook(String symbol, int depth) {
        Instrument instrument = instruments.require(symbol);
        long sequence = cursor(symbol);
        return new BookView(symbol, sequence,
                levels(symbol, Side.BUY, depth, instrument),
                levels(symbol, Side.SELL, depth, instrument));
    }

    private List<BookView.Level> levels(String symbol, Side side, int depth, Instrument instrument) {
        String order = side == Side.BUY ? "DESC" : "ASC";
        return jdbc.sql("SELECT price_ticks, total_lots FROM book_levels WHERE symbol = :s AND side = :side"
                        + " ORDER BY price_ticks " + order + " LIMIT :depth")
                .param("s", symbol)
                .param("side", side.name())
                .param("depth", depth)
                .query((rs, i) -> new BookView.Level(
                        instrument.price(rs.getLong("price_ticks")),
                        instrument.quantity(rs.getLong("total_lots"))))
                .list();
    }

    private OrderView mapOrder(ResultSet rs, int row) throws SQLException {
        String symbol = rs.getString("symbol");
        OrderStatus status = OrderStatus.valueOf(rs.getString("status"));
        String reason = rs.getString("reject_reason");
        String side = rs.getString("side");
        Instrument instrument = instruments.all().stream()
                .filter(i -> i.symbol().equals(symbol)).findFirst().orElse(null);

        BigDecimal price = null;
        BigDecimal quantity = null;
        BigDecimal remaining = null;
        BigDecimal filled = null;
        if (instrument != null && side != null) {
            long priceTicks = rs.getLong("price_ticks");
            price = rs.wasNull() ? null : instrument.price(priceTicks);
            quantity = instrument.quantity(rs.getLong("quantity_lots"));
            remaining = instrument.quantity(rs.getLong("remaining_lots"));
            filled = quantity.subtract(remaining);
            if (status == OrderStatus.CANCELLED) {
                // remaining_lots holds what was cancelled; nothing is working any more
                filled = quantity.subtract(remaining);
                remaining = BigDecimal.ZERO;
            }
        }
        return new OrderView(
                rs.getObject("id", UUID.class),
                rs.getString("account_id"),
                symbol,
                side == null ? null : Side.valueOf(side),
                rs.getString("type") == null ? null : OrderType.valueOf(rs.getString("type")),
                rs.getString("time_in_force") == null ? null : TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getBoolean("post_only"),
                price,
                quantity,
                filled,
                remaining,
                status,
                reason == null ? null : RejectReason.valueOf(reason),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
