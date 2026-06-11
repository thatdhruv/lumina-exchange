# Lumina Exchange — Video Walkthrough Script

Use this script when recording a 5–10 minute walkthrough of the platform. The UI at `http://localhost:3000` is the primary surface; follow the flow below so the recording tells a clear story.

**Before recording**

```powershell
cd path\to\lumina-exchange
docker compose up --build --remove-orphans
```

Wait until all services are healthy, then open **http://localhost:3000** in a clean browser window (no devtools unless you plan to show the network tab).

---

## 0:00 – 0:45 | Open on the console

**On screen:** Full trading dashboard at `localhost:3000`.

**Say something like:**

> "This is Lumina Exchange — a trading console backed by a Spring Boot matching engine. The UI talks to the API through the same origin; you can see the API health indicator in the header."

**Point out:**

- Title and symbol selector (default **BTC-USD**)
- Green **API healthy** badge (if red, the stack is not ready — wait for `app` to pass healthcheck)
- Three columns: order entry, order book, recent trades

---

## 0:45 – 2:00 | Architecture (optional voiceover)

**On screen:** Stay on the UI, or briefly show `docker compose ps` / a simple architecture slide if you have one.

**Cover in one pass:**

| Layer | What it does |
|-------|----------------|
| **Frontend** | Next.js 15 — order form, live book, trade feed |
| **API** | Spring Boot REST on `:8080` (proxied via the UI in Docker) |
| **Engine** | In-memory matching, price-time priority |
| **Kafka** | `orders.placed`, `trades.executed` events |
| **Redis** | Trade ledger populated from Kafka |
| **Ops** | Docker Compose locally; Helm chart for Kubernetes |

---

## 2:00 – 5:00 | Live trading flow (main segment)

Perform these steps **slowly** so viewers can follow the UI updates.

### Step 1 — Resting sell (30 s)

1. In **Place Order**, choose **SELL**, **LIMIT**
2. Symbol: **BTC-USD**, Price: **51000**, Quantity: **2**
3. Submit

**Show:** Ask side of the order book updates; order appears under **Session Orders**.

**Say:** "A limit sell rests on the book until a buyer crosses the spread."

### Step 2 — Matching buy (45 s)

1. Switch to **BUY**, **LIMIT**
2. Price: **51000** (same or better), Quantity: **2**
3. Submit

**Show:** Trade in **Recent Trades**; book depth changes; filled order status in session list.

**Say:** "The matcher fills at the resting price. Executed trades are published to Kafka and persisted in Redis for the feed you see here."

### Step 3 — Cancel (30 s)

1. Place another limit order that will **not** fill (e.g. BUY @ 40000)
2. Click **Cancel** in **Session Orders**

**Show:** Order removed from book / session list after refresh.

**Say:** "Open orders can be cancelled through the same API the UI uses."

### Step 4 — Second symbol (optional, 30 s)

1. Change symbol to **ETH-USD**
2. Place a small limit on each side

**Show:** Book and trades are per-symbol.

---

## 5:00 – 6:30 | Beyond the UI (optional)

Pick **one** of these if time allows:

- **Metrics:** Open `http://localhost:8080/actuator/prometheus` and search for `orders.placed` / `trades.executed`
- **Direct API:** `curl http://localhost:8080/api/v1/orderbook/BTC-USD`
- **gRPC:** Mention port **9090** and `order_service.proto` for programmatic clients

---

## 6:30 – 8:00 | Code pointers (optional)

**Frontend:** `frontend/src/lib/api.ts`, `components/OrderForm.tsx`, `OrderBookPanel.tsx`, `TradeFeed.tsx`

**Backend:** `OrderController`, `MatchingEngine`, `TradeExecutedConsumer`, `ExchangeMetrics`

**Tests:** `MatchingEngineTest` — partial fill scenario

---

## 8:00 – 9:00 | Close

**Say:**

> "Everything runs with `docker compose up`. The repo includes production Dockerfiles, a Helm chart, and Prometheus metrics. Thanks for watching."

**On screen:** Return to the console with a visible trade and healthy API badge.

---

## Troubleshooting during recording

| Issue | Fix |
|-------|-----|
| API unreachable in UI | Rebuild: `docker compose up --build`; wait for `app` healthy |
| Empty trade feed after fill | Wait 2–3 s for poll refresh, or switch symbol and back |
| Port in use | `docker compose down` then start again |

## Local frontend development (without Docker UI)

```powershell
# Terminal 1 — backend stack
docker compose up kafka redis app

# Terminal 2 — frontend dev server (proxies to localhost:8080)
cd frontend
npm install
npm run dev
```

Open **http://localhost:3000**. The dev server uses `API_INTERNAL_URL` default `http://localhost:8080` from `next.config.ts`.
