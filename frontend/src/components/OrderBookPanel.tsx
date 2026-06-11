"use client";

import { api, formatPrice, formatQty } from "@/lib/api";
import type { OrderBook } from "@/lib/types";
import { useEffect, useState } from "react";
import { Panel } from "./Panel";

interface OrderBookPanelProps {
  symbol: string;
  refreshKey: number;
}

export function OrderBookPanel({ symbol, refreshKey }: OrderBookPanelProps) {
  const [book, setBook] = useState<OrderBook | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const data = await api.getOrderBook(symbol);
        if (!cancelled) {
          setBook(data);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load order book");
        }
      }
    }

    load();
    const timer = setInterval(load, 1500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [symbol, refreshKey]);

  const maxQty = Math.max(
    ...(book?.bids ?? []).map((l) => l.quantity),
    ...(book?.asks ?? []).map((l) => l.quantity),
    1,
  );

  return (
    <Panel title="Order Book" subtitle={`Top levels · ${symbol}`} className="min-h-[420px]">
      {error && <p className="mb-3 text-xs text-ask">{error}</p>}
      <div className="grid grid-cols-2 gap-4 text-xs font-mono uppercase tracking-wider text-gray-500">
        <span>Bid Qty</span>
        <span className="text-right">Ask Qty</span>
      </div>
      <div className="mt-2 space-y-1">
        {Array.from({ length: 10 }).map((_, i) => {
          const bid = book?.bids[i];
          const ask = book?.asks[i];
          return (
            <div key={i} className="relative grid grid-cols-2 gap-2 py-0.5 font-mono text-sm">
              <div className="relative flex justify-between pr-2">
                {bid && (
                  <div
                    className="absolute inset-y-0 right-0 bg-bid/10"
                    style={{ width: `${(bid.quantity / maxQty) * 100}%` }}
                  />
                )}
                <span className="relative z-10 text-bid">{bid ? formatPrice(bid.price) : "—"}</span>
                <span className="relative z-10 text-gray-400">{bid ? formatQty(bid.quantity) : ""}</span>
              </div>
              <div className="relative flex justify-between pl-2">
                {ask && (
                  <div
                    className="absolute inset-y-0 left-0 bg-ask/10"
                    style={{ width: `${(ask.quantity / maxQty) * 100}%` }}
                  />
                )}
                <span className="relative z-10 text-gray-400">{ask ? formatQty(ask.quantity) : ""}</span>
                <span className="relative z-10 text-ask">{ask ? formatPrice(ask.price) : "—"}</span>
              </div>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}
