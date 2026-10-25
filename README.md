# Lumina Exchange

An event-sourced limit order book matching engine. Java 25, Spring Boot 4, Kafka, PostgreSQL,
Next.js 16. Built to scale by partitioning symbols, not by cloning a stateful process.

```
 clients ──REST/gRPC/SSE──▶ api ──command──▶ exchange.commands ──▶ engine ──events──▶ exchange.events
                             ▲   (Kafka, key = symbol, partitioned)   (one owner      │        │
                             │                                         per partition)  │        ▼
                             └──────── answer to my command, live market data ◀───────┘   projector ──▶ PostgreSQL
                                                                                              │
                                              reads: order lookup, depth, trades ◀────────────┘
```

| Role | What it does | Scales by |
|------|--------------|-----------|
| `api` | Validates, appends a command to the log, waits for the engine's answer. Serves reads from Postgres and live data over SSE. gRPC too. | Replicas + HPA |
| `engine` | The only writer for each command partition. Deterministic matching, snapshots, replay. | Replicas, up to the partition count |
| `projector` | Folds the event stream into Postgres (orders, trades, depth). Idempotent. | Replicas, up to the partition count |

One image runs every role (`LUMINA_ROLES=api|engine|projector`, default all three for local dev).
Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for why it is built this way and
[docs/OPERATIONS.md](docs/OPERATIONS.md) for running it.

## Quick start

```bash
docker compose up --build                       # Kafka, Postgres, api, engine, projector, UI
docker compose --profile observability up -d    # + Prometheus, Grafana (admin/admin), Jaeger
```

| | |
|--|--|
| Trading UI | http://localhost:3000 |
| REST | http://localhost:8080 |
| gRPC | localhost:9090 |
| Grafana / Jaeger | http://localhost:3001 / http://localhost:16686 |

Scale the engine and watch partitions move: `docker compose up -d --scale engine=2`.

## API

Order entry needs `X-API-Key` (dev keys `dev-key-trader-1`, `dev-key-trader-2`); market data is public.

```bash
# Rest a sell, then cross it. Idempotency-Key makes a retry return the original order.
curl -s -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-key-trader-1' -H 'Idempotency-Key: 7d1c' \
  -d '{"symbol":"BTC-USD","side":"SELL","type":"LIMIT","price":51000,"quantity":2}'

curl -s -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-key-trader-2' \
  -d '{"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":51000,"quantity":0.5}'
```

| Endpoint | Auth | |
|----------|------|--|
| `POST /api/v1/orders` | key | `201` placed (with immediate fills), `200` idempotent replay, `422` rejected or invalid, `429` rate limited, `504` engine did not answer (the command may still apply; look the order up) |
| `GET /api/v1/orders/{id}` · `GET /api/v1/orders?symbol=` | key | Your orders (open ones for the list) |
| `DELETE /api/v1/orders/{id}` | key | Cancel |
| `GET /api/v1/orderbook/{symbol}?depth=` | public | Aggregated depth + sequence |
| `GET /api/v1/trades/{symbol}?limit=` | public | Recent trades |
| `GET /api/v1/stream/{symbol}` | public | SSE: `snapshot`, then `book` and `trade` events |
| `GET /api/v1/instruments` | public | Tick size, lot size, quantity limits |

Order options: `type` LIMIT/MARKET, `timeInForce` GTC/IOC/FOK, `postOnly`. Self-trade prevention
cancels the incoming remainder. Prices and quantities must be multiples of the instrument's tick
and lot size ([application.yaml](src/main/resources/application.yaml)).

gRPC (`x-api-key` metadata) exposes `PlaceOrder`, `CancelOrder`, `GetOrderBook`:
[order_service.proto](src/main/proto/order_service.proto).

## Development

```bash
./mvnw verify                    # unit, property and Testcontainers tests (needs Docker); coverage gate on the engine
scripts/bench.sh                 # JMH engine benchmark
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
helm lint charts/lumina-exchange
```

What the tests cover:

- **Engine vs oracle**: property tests (jqwik) run random command streams through the engine and a
  deliberately naive reference book and require identical outcomes. They also assert the book is
  never crossed, sequence numbers never gap, lots are conserved, and that restoring a snapshot
  mid-stream changes nothing.
- **End to end** on real Kafka and Postgres: matching, cancel, idempotency, auth, validation, SSE,
  engine restart from snapshot, and engine rebuild from the full command log with the snapshot lost.

Engine benchmark on a developer laptop (one thread, includes command generation, short run):
about 2.2 million commands/s, p50 0.5 µs, p99 2.7 µs. This measures matching only. End-to-end order
latency is dominated by two Kafka round trips; see the Grafana dashboard for the real number.

## Deploying to Kubernetes

```bash
helm upgrade --install lumina charts/lumina-exchange -n lumina --create-namespace \
  --set kafka.bootstrapServers=... --set postgres.url=... --set postgres.existingSecret=... \
  --set security.existingSecret=...
```

The chart ships an `api` Deployment with HPA and PDB, an `engine` StatefulSet (fixed replicas,
static consumer-group membership, long termination grace for the final snapshot), a `projector`
Deployment, optional ServiceMonitor, PrometheusRule, NetworkPolicy and Ingress.
`deploy/k8s-dev/` has throwaway Kafka and Postgres plus values for kind/minikube.

## What this is not (yet)

It is a matching engine and its surrounding platform, not a complete exchange: there are no
accounts, balances, margin or risk checks, no settlement, and authentication is static API keys.
See "Known limits" in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
