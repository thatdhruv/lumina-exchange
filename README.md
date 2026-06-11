# Lumina Exchange

Distributed high-frequency trading engine built with Java 21, Spring Boot 3, Apache Kafka, Redis, gRPC, and Kubernetes.

## Architecture

```
                                                Lumina Exchange App
                         ┌────────────────────────────────────────────────────────────┐
  Clients ──────────────►│ REST :8080          gRPC :9090        /actuator/prometheus │
  (curl / grpcurl)       │       │                  │                      │          │
                         │       ▼                  ▼                      │          │
                         │  OrderController   OrderGrpcService             │          │
                         │       │                  │                      │          │
                         │       └────────┬─────────┘                      │          │
                         │                ▼                                │          │
                         │         OrderService ◄── ExchangeMetrics ───────┘          │
                         │                │                                           │
                         │                ▼                                           │
                         │        MatchingEngine (in-memory order book)               │
                         │                │                                           │
                         │     ┌──────────┴──────────┐                                │
                         │     ▼                     ▼                                │
                         │ OrderEventPublisher   OrderStore                           │
                         └─────┬─────────────────────┬────────────────────────────────┘
                               │                     │
                               ▼                     │
                    ┌──────────────────┐             │
                    │      Kafka       │             │
                    │ orders.placed    │             │
                    │ orders.cancelled │             │
                    │ trades.executed ─┼──► TradeExecutedConsumer
                    └──────────────────┘             │
                                                     ▼
                                              ┌─────────────────┐
                                              │  Redis Ledger   │
                                              │ trade:{id}      │
                                              │ trades:{symbol} │
                                              └────────┬────────┘
                                                       │
                         GET /api/v1/trades/{symbol} ◄─┘
                         (Resilience4j circuit breaker: redis-ledger)
```

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 21 | Local development |
| Maven | 3.9+ | Build |
| Docker | 24+ | Compose + Testcontainers |
| Docker Compose | v2 | Local stack |
| minikube | 1.32+ | Local Kubernetes |
| kubectl | 1.28+ | Kubernetes CLI |
| Helm | 3.12+ | Chart deployment |

## Quick start — Docker Compose

```bash
cd lumina-exchange
docker compose up --build
```

Wait until all services are healthy (~60 s). The stack runs **Kafka (KRaft)**, Redis, the Spring Boot API (`8080` / `9090`), and the **Next.js UI** (`3000`).

| Service | URL |
|---------|-----|
| Trading UI | http://localhost:3000 |
| REST API | http://localhost:8080 |
| gRPC | localhost:9090 |
| Prometheus | http://localhost:8080/actuator/prometheus |

### Frontend only (local dev)

```bash
cd frontend
npm install
NEXT_PUBLIC_API_URL=http://localhost:8080 npm run dev
```

## REST API examples

All examples assume the stack is running via Docker Compose.

### 1. Place a limit sell order

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"symbol":"BTC-USD","side":"SELL","type":"LIMIT","price":51000,"quantity":2}'
```

**Expected output (201 Created):**

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "symbol": "BTC-USD",
  "side": "SELL",
  "type": "LIMIT",
  "price": 51000,
  "quantity": 2,
  "status": "OPEN",
  "createdAt": "2026-06-09T12:00:00Z"
}
```

### 2. Place a matching buy order (triggers trade)

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":51000,"quantity":2}'
```

**Expected output:**

```json
{
  "id": "...",
  "symbol": "BTC-USD",
  "side": "BUY",
  "type": "LIMIT",
  "price": 51000,
  "quantity": 0,
  "status": "FILLED",
  "createdAt": "..."
}
```

### 3. Get order by ID

```bash
curl -s http://localhost:8080/api/v1/orders/{order-id}
```

**Expected output:** Same shape as place-order response with current status/quantity.

### 4. Cancel an open order

```bash
curl -s -X DELETE http://localhost:8080/api/v1/orders/{order-id}
```

**Expected output:**

```json
{
  "id": "...",
  "symbol": "BTC-USD",
  "side": "BUY",
  "type": "LIMIT",
  "price": 50000,
  "quantity": 1,
  "status": "CANCELLED",
  "createdAt": "..."
}
```

### 5. Get order book (top 10 levels)

```bash
curl -s http://localhost:8080/api/v1/orderbook/BTC-USD
```

**Expected output:**

```json
{
  "symbol": "BTC-USD",
  "bids": [
    {"price": 50000, "quantity": 3}
  ],
  "asks": [
    {"price": 51000, "quantity": 1}
  ]
}
```

### 6. Get recent trades from Redis

```bash
curl -s "http://localhost:8080/api/v1/trades/BTC-USD?limit=50"
```

**Expected output (after a match):**

```json
[
  {
    "id": "...",
    "buyOrderId": "...",
    "sellOrderId": "...",
    "symbol": "BTC-USD",
    "price": 51000,
    "quantity": 2,
    "executedAt": "2026-06-09T12:00:01Z"
  }
]
```

**Expected output (no trades yet):** `[]`

**Expected output (Redis down, circuit breaker open):** `[]` (fallback)

## Prometheus metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "orders_|trades_|matching_|orderbook_|resilience4j"
```

**Expected sample lines:**

```
orders_placed_total{application="lumina-exchange",side="BUY",symbol="BTC-USD"} 1.0
orders_cancelled_total{application="lumina-exchange",symbol="BTC-USD"} 0.0
trades_executed_total{application="lumina-exchange",symbol="BTC-USD"} 1.0
matching_latency_ms_seconds_count{application="lumina-exchange",symbol="BTC-USD"} 1.0
orderbook_depth{application="lumina-exchange",symbol="BTC-USD"} 0.0
resilience4j_circuitbreaker_state{name="redis-ledger"} 0.0
```

## gRPC (port 9090)

Install [grpcurl](https://github.com/fullstorydev/grpcurl):

```bash
grpcurl -plaintext -d '{"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":"50000","quantity":"1"}' \
  localhost:9090 lumina.orderbook.OrderService/PlaceOrder
```

**Expected output:**

```json
{
  "id": "...",
  "symbol": "BTC-USD",
  "side": "BUY",
  "type": "LIMIT",
  "price": "50000",
  "quantity": "1",
  "status": "OPEN",
  "createdAt": "..."
}
```

```bash
grpcurl -plaintext -d '{"symbol":"BTC-USD"}' \
  localhost:9090 lumina.orderbook.OrderService/GetOrderBook
```

## Run tests

```bash
mvn verify
```

- **Unit tests** — matching engine, services, controllers (always run)
- **Integration tests** — Kafka + Redis via Testcontainers (require Docker; skipped if unavailable)

## Kubernetes (minikube)

### 1. Start minikube and build the image

```bash
minikube start --cpus=4 --memory=8192
eval $(minikube docker-env)          # Linux/macOS
# minikube docker-env | Invoke-Expression   # PowerShell
docker build -t lumina-exchange:0.3.0 .
```

### 2. Deploy Kafka and Redis (in-cluster)

```bash
kubectl create namespace lumina
kubectl apply -f k8s/infra/ -n lumina
kubectl wait --for=condition=ready pod -l app=kafka -n lumina --timeout=120s
```

### 3. Install via Helm

```bash
helm upgrade --install lumina charts/lumina-exchange \
  -n lumina \
  --set image.repository=lumina-exchange \
  --set image.tag=0.3.0 \
  --set image.pullPolicy=Never \
  --set config.kafkaBootstrapServers=kafka:9092 \
  --set config.redisHost=redis
```

### 4. Access the API

```bash
minikube service lumina-lumina-exchange-api -n lumina --url
# curl the returned URL, e.g. http://192.168.49.2:31234/api/v1/orderbook/BTC-USD
```

For LoadBalancer on minikube, run `minikube tunnel` in a separate terminal.

### 5. Verify HPA and metrics

```bash
kubectl get hpa -n lumina
kubectl get servicemonitor -n lumina          # requires Prometheus Operator
kubectl port-forward svc/lumina-lumina-exchange-internal 8080:8080 -n lumina
curl -s localhost:8080/actuator/prometheus | head
```

## Project structure

```
src/main/java/com/lumina/orderbook/
  api/           REST controllers
  config/        Kafka, Resilience4j metrics
  engine/        Matching engine
  event/         Kafka event DTOs
  grpc/          gRPC service
  kafka/         Producer + consumer
  metrics/       Micrometer counters, timers, gauges
  redis/         Trade ledger repository
  service/       Business logic
charts/lumina-exchange/   Helm chart
k8s/infra/                Kafka + Redis manifests for minikube
docker-compose.yml        Local full stack
```

## Phases completed

| Phase | Features |
|-------|----------|
| 1 | In-memory order book, REST API, matching engine tests |
| 2 | Kafka events, Redis trade ledger, gRPC, Docker Compose |
| 3 | Prometheus metrics, Resilience4j circuit breaker, Helm/K8s |
| 4 | Next.js TypeScript trading console (full-stack demo) |
