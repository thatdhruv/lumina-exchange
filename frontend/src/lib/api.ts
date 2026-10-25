import type {
  ApiErrorBody,
  Instrument,
  Order,
  OrderBook,
  PlaceOrderRequest,
  Trade,
} from "./types";

// Same-origin: /api/v1/* is proxied to the exchange by a route handler that adds the API key.
const BASE = "/api/v1";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody,
  ) {
    super(body.message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
    cache: "no-store",
  });

  if (!response.ok) {
    let body: ApiErrorBody;
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      body = { code: "HTTP_" + response.status, message: `Request failed (${response.status})` };
    }
    throw new ApiError(response.status, body);
  }
  return (await response.json()) as T;
}

export const api = {
  instruments: () => request<Instrument[]>("/instruments"),

  orderBook: (symbol: string, depth = 20) =>
    request<OrderBook>(`/orderbook/${encodeURIComponent(symbol)}?depth=${depth}`),

  trades: (symbol: string, limit = 50) =>
    request<Trade[]>(`/trades/${encodeURIComponent(symbol)}?limit=${limit}`),

  openOrders: (symbol?: string) =>
    request<Order[]>(symbol ? `/orders?symbol=${encodeURIComponent(symbol)}` : "/orders"),

  // A fresh key per submit: retrying the same request after a timeout cannot double-fill.
  placeOrder: (body: PlaceOrderRequest, idempotencyKey: string) =>
    request<Order>("/orders", {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  cancelOrder: (id: string) => request<Order>(`/orders/${id}`, { method: "DELETE" }),

  streamUrl: (symbol: string) => `${BASE}/stream/${encodeURIComponent(symbol)}`,
};

export function formatPrice(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function formatQty(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 6 });
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

/** Decimal places implied by a tick or lot size such as 0.01 or 0.0001. */
export function decimalsOf(step: number): number {
  const text = step.toString();
  if (text.includes("e-")) return Number(text.split("e-")[1]);
  return text.includes(".") ? text.split(".")[1].length : 0;
}
