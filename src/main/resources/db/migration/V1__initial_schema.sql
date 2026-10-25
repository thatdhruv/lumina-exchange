-- Engine state: one snapshot per command-topic partition, replayed from kafka_offset + 1 on recovery.
CREATE TABLE engine_snapshots (
    partition    INT         PRIMARY KEY,
    kafka_offset BIGINT      NOT NULL,
    payload      BYTEA       NOT NULL,
    taken_at     TIMESTAMPTZ NOT NULL
);

-- Read-side projection of exchange.events. last_seq makes every update idempotent.
CREATE TABLE projection_cursor (
    symbol     TEXT        PRIMARY KEY,
    last_seq   BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders (
    id             UUID        PRIMARY KEY,
    account_id     TEXT        NOT NULL,
    symbol         TEXT        NOT NULL,
    side           TEXT,
    type           TEXT,
    time_in_force  TEXT,
    post_only      BOOLEAN     NOT NULL DEFAULT FALSE,
    price_ticks    BIGINT,
    quantity_lots  BIGINT,
    remaining_lots BIGINT,
    status         TEXT        NOT NULL,
    reject_reason  TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX orders_open_by_account ON orders (account_id, created_at DESC)
    WHERE status IN ('OPEN', 'PARTIAL');

CREATE TABLE trades (
    id            UUID        PRIMARY KEY,
    symbol        TEXT        NOT NULL,
    seq           BIGINT      NOT NULL,
    price_ticks   BIGINT      NOT NULL,
    quantity_lots BIGINT      NOT NULL,
    taker_side    TEXT        NOT NULL,
    buy_order_id  UUID        NOT NULL,
    sell_order_id UUID        NOT NULL,
    executed_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX trades_by_symbol ON trades (symbol, seq DESC);

CREATE TABLE book_levels (
    symbol      TEXT   NOT NULL,
    side        TEXT   NOT NULL,
    price_ticks BIGINT NOT NULL,
    total_lots  BIGINT NOT NULL,
    PRIMARY KEY (symbol, side, price_ticks)
);
