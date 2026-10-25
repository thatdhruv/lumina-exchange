"use client";

import { api } from "@/lib/api";
import { applySnapshot, applyUpdate, EMPTY_BOOK } from "@/lib/book";
import type { OrderBook, StreamBookUpdate, StreamSnapshot, StreamTrade, Trade } from "@/lib/types";
import { useEffect, useState } from "react";

const MAX_TRADES = 50;

export interface MarketStream {
  book: OrderBook;
  trades: Trade[];
  connected: boolean;
  /** Bumps on every trade so dependents (open orders) know to refresh. */
  tradeCount: number;
}

/**
 * Live book and trades for one symbol over server-sent events. EventSource reconnects by itself;
 * every reconnect starts with a fresh snapshot, so the book cannot drift.
 */
export function useMarketStream(symbol: string): MarketStream {
  const [book, setBook] = useState<OrderBook>({ ...EMPTY_BOOK, symbol });
  const [trades, setTrades] = useState<Trade[]>([]);
  const [connected, setConnected] = useState(false);
  const [tradeCount, setTradeCount] = useState(0);

  useEffect(() => {
    let active = true;
    // Resetting per-symbol state when the symbol changes is exactly what this effect is for.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setBook({ ...EMPTY_BOOK, symbol });
    setTrades([]);
    setConnected(false);

    api
      .trades(symbol, MAX_TRADES)
      .then((history) => active && setTrades((live) => merge(live, history)))
      .catch(() => undefined);

    const source = new EventSource(api.streamUrl(symbol));
    source.onopen = () => active && setConnected(true);
    source.onerror = () => active && setConnected(false);

    source.addEventListener("snapshot", (event) => {
      const snapshot = JSON.parse((event as MessageEvent<string>).data) as StreamSnapshot;
      if (active) setBook((current) => applySnapshot(current, snapshot));
    });
    source.addEventListener("book", (event) => {
      const update = JSON.parse((event as MessageEvent<string>).data) as StreamBookUpdate;
      if (active) setBook((current) => applyUpdate(current, update));
    });
    source.addEventListener("trade", (event) => {
      const trade = JSON.parse((event as MessageEvent<string>).data) as StreamTrade;
      if (!active) return;
      setTrades((current) => merge(current, [trade]));
      setTradeCount((n) => n + 1);
    });

    return () => {
      active = false;
      source.close();
    };
  }, [symbol]);

  return { book, trades, connected, tradeCount };
}

/** Newest first, de-duplicated by id (history and the live stream overlap briefly). */
function merge(current: Trade[], incoming: Trade[]): Trade[] {
  const byId = new Map<string, Trade>();
  for (const trade of [...incoming, ...current]) byId.set(trade.id, trade);
  return [...byId.values()].sort((a, b) => b.sequence - a.sequence).slice(0, MAX_TRADES);
}
