"use client";

import { api, formatPrice, formatQty, formatTime } from "@/lib/api";
import type { Trade } from "@/lib/types";
import { useEffect, useState } from "react";
import { Panel } from "./Panel";

interface TradeFeedProps {
  symbol: string;
  refreshKey: number;
}

export function TradeFeed({ symbol, refreshKey }: TradeFeedProps) {
  const [trades, setTrades] = useState<Trade[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const data = await api.getTrades(symbol);
        if (!cancelled) {
          setTrades(data);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load trades");
        }
      }
    }

    load();
    const timer = setInterval(load, 2000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [symbol, refreshKey]);

  return (
    <Panel
      title="Recent Trades"
      subtitle="Kafka → trades.executed → Redis ledger"
      className="min-h-[320px]"
    >
      {error && <p className="mb-2 text-xs text-ask">{error}</p>}
      {trades.length === 0 ? (
        <p className="text-sm text-gray-500">No trades yet — place matching orders to see the pipeline.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left font-mono text-xs">
            <thead className="text-gray-500">
              <tr>
                <th className="pb-2 pr-3">Time</th>
                <th className="pb-2 pr-3">Price</th>
                <th className="pb-2">Qty</th>
              </tr>
            </thead>
            <tbody>
              {trades.map((trade) => (
                <tr key={trade.id} className="border-t border-surface-border/60">
                  <td className="py-2 pr-3 text-gray-400">{formatTime(trade.executedAt)}</td>
                  <td className="py-2 pr-3 text-accent">{formatPrice(trade.price)}</td>
                  <td className="py-2">{formatQty(trade.quantity)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
