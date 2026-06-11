# Daxwell Interview — 5–10 Minute Video Script

Use this outline when recording your walkthrough for the Software Developer submission.

**Submission checklist**
- [ ] GitHub repo link (public): `https://github.com/thatdhruv/lumina-exchange`
- [ ] Video uploaded to DevRev Dropbox (passcode: `InterviewDaxwell123@`)
- [ ] Length: 5–10 minutes

---

## 0:00 – 0:45 | Introduction

> "Hi, I'm Dhruv. This is **Lumina Exchange** — a full-stack enterprise trading platform I built to demonstrate skills aligned with the Daxwell role: **Next.js and TypeScript** on the front end, **Spring Boot and Java** on the back end, with **Kafka**, **Redis**, **Docker**, **Kubernetes**, and **Prometheus** observability."

Show: browser at `http://localhost:3000` with the trading console.

---

## 0:45 – 2:00 | Architecture

1. **Next.js UI** → REST calls to Spring Boot (`/api/v1/...`)
2. **Matching engine** — in-memory order book, price-time priority
3. **Kafka** — `orders.placed`, `trades.executed` events
4. **Redis** — trade ledger consumed from Kafka
5. **gRPC** on port 9090 for programmatic clients
6. **Prometheus** metrics + Resilience4j circuit breaker on Redis
7. **Helm chart** for Kubernetes deployment

---

## 2:00 – 5:00 | Live demo

```powershell
docker compose up --build
```

1. Green "API healthy" badge
2. Place LIMIT SELL BTC-USD @ 51000, qty 2
3. Place matching LIMIT BUY — trade appears in Recent Trades
4. Cancel an open order from Session Orders
5. Optional: `curl http://localhost:8080/actuator/prometheus`

---

## 5:00 – 7:30 | Code walkthrough

**Frontend:** `lib/api.ts`, `lib/types.ts`, `components/`, `app/page.tsx`

**Backend:** `OrderController`, `OrderService`, `MatchingEngine`, `TradeExecutedConsumer`, `ExchangeMetrics`

**Tests:** `MatchingEngineTest` partial fill scenario

---

## 7:30 – 8:30 | DevOps

- `docker-compose.yml` — full stack including frontend
- Multi-stage Dockerfiles, Helm chart, HPA, ServiceMonitor
- `mvn verify` + Testcontainers

---

## 8:30 – 9:30 | JD mapping

| JD requirement | This project |
|----------------|--------------|
| Next.js / React | Trading console UI |
| Spring Boot / Java | Order book API + engine |
| TypeScript | Frontend |
| Docker / Kubernetes | Compose + Helm |
| MVC | Controller → Service → Engine |

---

## Quick start

```powershell
docker compose up --build
# UI:  http://localhost:3000
# API: http://localhost:8080
```

Local frontend dev:

```powershell
cd frontend
npm install
$env:NEXT_PUBLIC_API_URL="http://localhost:8080"
npm run dev
```
