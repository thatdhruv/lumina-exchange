"use client";

import { api, formatPrice, formatQty, formatTime } from "@/lib/api";
import type { Order, OrderSide, OrderType } from "@/lib/types";
import { FormEvent, useState } from "react";
import { Panel } from "./Panel";

const SYMBOLS = ["BTC-USD", "ETH-USD", "SOL-USD"];

interface OrderFormProps {
  symbol: string;
  onSymbolChange: (symbol: string) => void;
  onOrderPlaced: (order: Order) => void;
}

export function OrderForm({ symbol, onSymbolChange, onOrderPlaced }: OrderFormProps) {
  const [side, setSide] = useState<OrderSide>("BUY");
  const [type, setType] = useState<OrderType>("LIMIT");
  const [price, setPrice] = useState("50000");
  const [quantity, setQuantity] = useState("1");
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    setMessage(null);

    try {
      const order = await api.placeOrder({
        symbol,
        side,
        type,
        quantity: Number(quantity),
        price: type === "LIMIT" ? Number(price) : undefined,
      });
      onOrderPlaced(order);
      setMessage(`${order.side} ${order.status} · ${order.id.slice(0, 8)}…`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Order failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <Panel title="Place Order" subtitle="REST → Spring Boot matching engine">
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="mb-1 block text-xs text-gray-500">Symbol</label>
          <select
            className="input"
            value={symbol}
            onChange={(e) => onSymbolChange(e.target.value)}
          >
            {SYMBOLS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-2">
          {(["BUY", "SELL"] as OrderSide[]).map((s) => (
            <button
              key={s}
              type="button"
              className={side === s ? (s === "BUY" ? "btn-buy" : "btn-sell") : "btn-ghost"}
              onClick={() => setSide(s)}
            >
              {s}
            </button>
          ))}
        </div>

        <div className="grid grid-cols-2 gap-2">
          {(["LIMIT", "MARKET"] as OrderType[]).map((t) => (
            <button
              key={t}
              type="button"
              className={type === t ? "btn border border-accent text-accent" : "btn-ghost"}
              onClick={() => setType(t)}
            >
              {t}
            </button>
          ))}
        </div>

        {type === "LIMIT" && (
          <div>
            <label className="mb-1 block text-xs text-gray-500">Price</label>
            <input
              className="input"
              type="number"
              min="0"
              step="0.01"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              required
            />
          </div>
        )}

        <div>
          <label className="mb-1 block text-xs text-gray-500">Quantity</label>
          <input
            className="input"
            type="number"
            min="0"
            step="0.0001"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            required
          />
        </div>

        <button
          type="submit"
          disabled={loading}
          className={`w-full ${side === "BUY" ? "btn-buy" : "btn-sell"}`}
        >
          {loading ? "Submitting…" : `${side} ${symbol}`}
        </button>

        {message && <p className="text-xs text-bid">{message}</p>}
        {error && <p className="text-xs text-ask">{error}</p>}
      </form>
    </Panel>
  );
}

interface OpenOrdersProps {
  orders: Order[];
  onCancel: (id: string) => void;
}

export function OpenOrdersPanel({ orders, onCancel }: OpenOrdersProps) {
  const open = orders.filter((o) => o.status === "OPEN" || o.status === "PARTIAL");

  return (
    <Panel title="Session Orders" subtitle="In-memory store · cancel via API">
      {open.length === 0 ? (
        <p className="text-sm text-gray-500">No open orders in this session.</p>
      ) : (
        <ul className="space-y-2">
          {open.map((order) => (
            <li
              key={order.id}
              className="flex items-center justify-between gap-2 rounded-lg border border-surface-border px-3 py-2 font-mono text-xs"
            >
              <div>
                <span className={order.side === "BUY" ? "text-bid" : "text-ask"}>{order.side}</span>
                <span className="ml-2 text-gray-400">{formatQty(order.quantity)} @ {formatPrice(order.price)}</span>
                <span className="ml-2 text-gray-600">{formatTime(order.createdAt)}</span>
              </div>
              <button type="button" className="btn-ghost px-2 py-1 text-xs" onClick={() => onCancel(order.id)}>
                Cancel
              </button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
