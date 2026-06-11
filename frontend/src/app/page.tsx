"use client";

import { api } from "@/lib/api";
import type { Order } from "@/lib/types";
import { useCallback, useState } from "react";
import { Header } from "@/components/Header";
import { OpenOrdersPanel, OrderForm } from "@/components/OrderForm";
import { OrderBookPanel } from "@/components/OrderBookPanel";
import { TradeFeed } from "@/components/TradeFeed";

export default function TradingDashboard() {
  const [symbol, setSymbol] = useState("BTC-USD");
  const [orders, setOrders] = useState<Order[]>([]);
  const [refreshKey, setRefreshKey] = useState(0);

  const bump = useCallback(() => setRefreshKey((k) => k + 1), []);

  function handleOrderPlaced(order: Order) {
    setOrders((prev) => [order, ...prev]);
    bump();
  }

  async function handleCancel(id: string) {
    try {
      const cancelled = await api.cancelOrder(id);
      setOrders((prev) => prev.map((o) => (o.id === id ? cancelled : o)));
      bump();
    } catch (err) {
      console.error(err);
    }
  }

  return (
    <div className="mx-auto min-h-screen max-w-7xl px-4 py-8 md:px-6">
      <Header />

      <div className="grid gap-4 lg:grid-cols-12">
        <div className="lg:col-span-4 space-y-4">
          <OrderForm
            symbol={symbol}
            onSymbolChange={setSymbol}
            onOrderPlaced={handleOrderPlaced}
          />
          <OpenOrdersPanel orders={orders} onCancel={handleCancel} />
        </div>

        <div className="lg:col-span-5">
          <OrderBookPanel symbol={symbol} refreshKey={refreshKey} />
        </div>

        <div className="lg:col-span-3 space-y-4">
          <TradeFeed symbol={symbol} refreshKey={refreshKey} />
          <section className="panel p-4 text-xs text-gray-400">
            <h3 className="mb-2 font-semibold text-gray-200">Demo flow for interview</h3>
            <ol className="list-decimal space-y-1 pl-4">
              <li>Place a LIMIT SELL — order rests on the book</li>
              <li>Place a matching BUY — engine fills &amp; emits Kafka events</li>
              <li>Watch trades appear in Redis-backed feed</li>
              <li>Show metrics at <code className="text-accent">/actuator/prometheus</code></li>
            </ol>
          </section>
        </div>
      </div>
    </div>
  );
}
