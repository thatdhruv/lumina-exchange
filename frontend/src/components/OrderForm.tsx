"use client";

import { api, ApiError, decimalsOf, formatQty } from "@/lib/api";
import type { Instrument, Order, OrderSide, OrderType, TimeInForce } from "@/lib/types";
import { FormEvent, useState } from "react";
import { Panel } from "./Panel";

interface OrderFormProps {
  instruments: Instrument[];
  symbol: string;
  onSymbolChange: (symbol: string) => void;
  onOrderPlaced: (order: Order) => void;
}

export function OrderForm({ instruments, symbol, onSymbolChange, onOrderPlaced }: OrderFormProps) {
  const [side, setSide] = useState<OrderSide>("BUY");
  const [type, setType] = useState<OrderType>("LIMIT");
  const [timeInForce, setTimeInForce] = useState<TimeInForce>("GTC");
  const [postOnly, setPostOnly] = useState(false);
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const instrument = instruments.find((i) => i.symbol === symbol);
  const priceDigits = instrument ? decimalsOf(instrument.tickSize) : 2;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    setMessage(null);

    try {
      const order = await api.placeOrder(
        {
          symbol,
          side,
          type,
          timeInForce: type === "MARKET" && timeInForce === "GTC" ? "IOC" : timeInForce,
          postOnly: type === "LIMIT" && timeInForce === "GTC" ? postOnly : false,
          quantity: Number(quantity),
          price: type === "LIMIT" ? Number(price) : undefined,
        },
        crypto.randomUUID(),
      );
      onOrderPlaced(order);
      setMessage(`${order.side} ${order.status} · filled ${formatQty(order.filledQuantity)} of ${formatQty(order.quantity)}`);
    } catch (err) {
      if (err instanceof ApiError) {
        const fields = err.body.fields ? Object.entries(err.body.fields).map(([k, v]) => `${k}: ${v}`).join(", ") : "";
        setError(`${err.body.code}: ${fields || err.body.message}`);
      } else {
        setError("Order failed");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <Panel title="Place Order" subtitle="Appended to the command log, matched by the engine">
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="symbol" className="mb-1 block text-xs text-gray-500">Symbol</label>
          <select id="symbol" className="input" value={symbol} onChange={(e) => onSymbolChange(e.target.value)}>
            {instruments.map((i) => (
              <option key={i.symbol} value={i.symbol}>
                {i.symbol}
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
            <label htmlFor="price" className="mb-1 block text-xs text-gray-500">Price</label>
            <input
              id="price"
              className="input"
              type="number"
              min="0"
              step={instrument?.tickSize ?? "any"}
              placeholder={priceDigits ? `0.${"0".repeat(priceDigits)}` : "0"}
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              required
            />
          </div>
        )}

        <div>
          <label htmlFor="quantity" className="mb-1 block text-xs text-gray-500">Quantity</label>
          <input
            id="quantity"
            className="input"
            type="number"
            min={instrument?.minQuantity ?? 0}
            max={instrument?.maxQuantity}
            step={instrument?.lotSize ?? "any"}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            required
          />
        </div>

        <div className="grid grid-cols-2 items-end gap-2">
          <div>
            <label htmlFor="tif" className="mb-1 block text-xs text-gray-500">Time in force</label>
            <select
              id="tif"
              className="input"
              value={timeInForce}
              onChange={(e) => setTimeInForce(e.target.value as TimeInForce)}
            >
              <option value="GTC">GTC (rest)</option>
              <option value="IOC">IOC (fill or cancel rest)</option>
              <option value="FOK">FOK (all or nothing)</option>
            </select>
          </div>
          <label className="flex items-center gap-2 pb-2 text-xs text-gray-400">
            <input
              type="checkbox"
              checked={postOnly}
              disabled={type !== "LIMIT" || timeInForce !== "GTC"}
              onChange={(e) => setPostOnly(e.target.checked)}
            />
            Post only
          </label>
        </div>

        <button type="submit" disabled={loading} className={`w-full ${side === "BUY" ? "btn-buy" : "btn-sell"}`}>
          {loading ? "Submitting…" : `${side} ${symbol}`}
        </button>

        {message && <p className="text-xs text-bid">{message}</p>}
        {error && <p className="text-xs text-ask" role="alert">{error}</p>}
      </form>
    </Panel>
  );
}
