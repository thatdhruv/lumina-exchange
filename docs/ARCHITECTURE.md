# Architecture

## The problem with the original design

The first version kept each order book in the memory of whichever pod handled the request, and the
Helm chart ran 2 to 6 of them behind an HPA. Every replica had its own book, so a buy on one pod
could never match a sell on another, an order lookup could 404 depending on the load balancer, and
any restart lost everything. A matching engine is a single writer per symbol. The way to scale it
is to partition symbols across writers and make the writer recoverable, not to clone it.

## Shape

```
api ── Command (protobuf) ──▶ exchange.commands  ── key = symbol, N partitions, retained forever
                                      │
                         engine: one consumer per partition  (consumer-group ownership)
                                      │  deterministic: same log in, same events out
                                      ▼
                              exchange.events  ── EventBatch per command, key = symbol
                           ┌──────────┼─────────────────┐
                           ▼          ▼                 ▼
                     api (all pods)  projector      (future consumers:
                     answers callers, folds into     risk, surveillance,
                     SSE market data Postgres        market-data fan-out)
```

**The command log is the source of truth.** The engine's in-memory books are a cache of it. Kafka's
consumer-group protocol gives single-writer ownership and failover for free: a partition has
exactly one consumer at a time, and when it dies another takes over.

### Determinism is the load-bearing property

The engine never reads a clock, a random source or a hash order. Timestamps are stamped by the
gateway and carried in the command; sequence numbers are per symbol and gap-free; trade ids are
`UUIDv3("symbol:seq")`. So replaying the same commands always produces byte-identical events.
That buys three things:

1. **Recovery** = latest snapshot + replay of the log after it.
2. **Safe duplicates.** After a crash the new owner re-emits events from the snapshot onward. They
   are identical to what was already published, and every consumer dedupes on `(symbol, seq)`.
   There is no exactly-once machinery and none is needed.
3. **Zombie safety.** If an old owner lingers briefly after a rebalance, anything it publishes is
   what the new owner would publish anyway.

This is tested directly: property tests restore a snapshot mid-stream and require the tail of events
to be identical, and the integration tests restart the engine with and without its snapshot.

### Request path

1. `api` validates against the instrument (tick, lot, limits), converts to integer ticks/lots,
   derives a deterministic order id from `Idempotency-Key` if given, and produces the command.
2. The engine consumes it, matches, and publishes one `EventBatch` for that command
   (`Accepted`, `Trade`*, `Cancelled`/`Rejected`, `BookChanged`).
3. Each `api` pod reads the event stream under its own throwaway consumer group, completes the
   future waiting on that command id, and replies. Latency is therefore two Kafka hops.
4. The `projector` writes the same events to Postgres. Reads (order lookup, depth, trades) come
   from there and are **eventually consistent** with the stream by a few milliseconds.

### Engine internals

- Per-price FIFO queues as intrusive doubly linked lists in a `TreeMap` of levels: insert
  O(log levels), cancel O(1), best price O(1). The old book rebuilt a `PriorityQueue` on every
  cancel (O(n)) and re-sorted on every depth read.
- Prices and quantities are `long` ticks and lots; `BigDecimal` exists only at the API edge.
- Time priority comes from a per-book arrival counter, not wall-clock time.
- Order types: limit, market, IOC, FOK, post-only; self-trade prevention (cancel newest).
- Duplicate protection: live orders plus a bounded memory of recently finished ids (snapshotted).
- `BookChanged` events carry **absolute** level quantities, so applying one twice is harmless.

### Snapshots

Every N commands or T seconds (and on graceful shutdown) a partition writes its books to Postgres
(`engine_snapshots`, one row per partition, only ever moving forward). Output is flushed to Kafka
before a snapshot is taken, so a snapshot never describes state whose events were not published.
On assignment the engine loads the snapshot and seeks to its offset + 1; the consumer group's
committed offset is irrelevant. If a batch fails to publish, the partition is rebuilt from its
snapshot and replayed rather than continuing with state that has run ahead of the published events.

### Read side

The projector applies an event only if its sequence is beyond that symbol's cursor, inside one
transaction with the cursor update. Redelivery, replay and rebalances therefore cannot double-apply.
A sequence gap raises an alert (`lumina_projector_gaps_total`). The read model can always be rebuilt
by truncating its tables and resetting the projector's consumer group to earliest.

### Live market data

`GET /stream/{symbol}` sends a snapshot from Postgres, then `book` and `trade` events from the
gateway's own copy of the event stream, and re-sends the snapshot every few seconds. Snapshots and
updates carry the stream sequence; the client keeps a snapshot only if it is not older than what it
shows, and level updates are absolute, so a lagging read model cannot roll the book back and a
missed update heals on the next snapshot. A slow client is dropped rather than allowed to stall
the Kafka consumer thread.

## Technology choices

| Choice | Why |
|--------|-----|
| Java 25 (LTS), virtual threads for blocking waits, ZGC | The bottleneck was the architecture, not the language; the JVM is the right tool for a service this size. A native rewrite only pays at microsecond end-to-end targets, and Kafka is already in the path. |
| Spring Boot 4 | 3.x is out of open-source support. Boot 4.1 also ships native gRPC support, replacing the community starter. |
| Kafka as command log | Ordered, partitioned, replicated, retained, with consumer-group failover. Retention is infinite on commands. |
| Protobuf wire format | Compact, typed, evolvable. Compatibility rules are in `exchange.proto`. A schema registry is the next step once more than one team consumes the topics. |
| PostgreSQL read model and snapshot store | Durable, queryable history in place of Redis hashes. One fewer moving part than adding another store for snapshots. |
| Next.js 16, Tailwind 4, SSE | Live data without polling; the API key stays server-side in a route handler. |

## Known limits

- **Partition count is fixed** for the life of the cluster; symbol-to-partition ownership depends
  on it. Pick it with headroom (default 6). Changing it needs a migration, not a config edit.
- **Failover time** is the Kafka session timeout (10 s here) plus replay since the last snapshot.
  Orders to an unowned partition time out with 504 and may still apply afterwards, which is why
  the API tells clients to look the order up and why idempotency keys exist.
- **Every gateway pod reads the whole event stream.** Fine into the tens of thousands of events per
  second; beyond that, split market-data fan-out into its own service.
- **Reads are eventually consistent.** A cancel issued within milliseconds of placing an order can
  404 until the projector catches up.
- **One hot symbol is one partition**, so one core. Per-symbol throughput is bounded by the engine
  benchmark, not by the number of replicas.
- **No accounts, balances or risk checks**, no settlement, static API keys, per-pod rate limits.
  These are the next layer to build on the event stream (a risk service consuming events and
  gating commands), not part of the matching core.
- Lost-or-corrupt snapshots fall back to full replay, which is correct but slow for a long log;
  compacting the command log behind snapshots is not implemented.
