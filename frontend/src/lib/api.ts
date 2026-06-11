import type {
  HealthResponse,
  Order,
  OrderBook,
  PlaceOrderRequest,
  Trade,
} from "./types";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
    cache: "no-store",
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(detail || `Request failed: ${response.status}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

export const api = {
  health: () => request<HealthResponse>("/actuator/health"),

  getOrderBook: (symbol: string) =>
    request<OrderBook>(`/api/v1/orderbook/${encodeURIComponent(symbol)}`),

  getTrades: (symbol: string, limit = 50) =>
    request<Trade[]>(
      `/api/v1/trades/${encodeURIComponent(symbol)}?limit=${limit}`,
    ),

  placeOrder: (body: PlaceOrderRequest) =>
    request<Order>("/api/v1/orders", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  cancelOrder: (id: string) =>
    request<Order>(`/api/v1/orders/${id}`, { method: "DELETE" }),

  getOrder: (id: string) => request<Order>(`/api/v1/orders/${id}`),
};

export function formatPrice(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export function formatQty(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}
