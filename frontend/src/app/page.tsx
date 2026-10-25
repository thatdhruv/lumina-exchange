"use client";

import { Footer } from "@/components/Footer";
import { Header } from "@/components/Header";
import { OpenOrdersPanel } from "@/components/OpenOrdersPanel";
import { OrderBookPanel } from "@/components/OrderBookPanel";
import { OrderForm } from "@/components/OrderForm";
import { TradeFeed } from "@/components/TradeFeed";
import { useMarketStream } from "@/hooks/useMarketStream";
import { api, decimalsOf } from "@/lib/api";
import type { Instrument, Order } from "@/lib/types";
import { useCallback, useEffect, useState } from "react";

export default function TradingDashboard() {
  const [instruments, setInstruments] = useState<Instrument[]>([]);
  const [symbol, setSymbol] = useState("BTC-USD");
  const [openOrders, setOpenOrders] = useState<Order[]>([]);
  const { book, trades, connected, tradeCount } = useMarketStream(symbol);

  const refreshOrders = useCallback(async () => {
    try {
      setOpenOrders(await api.openOrders(symbol));
    } catch {
      // the next trade or placement retries
    }
  }, [symbol]);

  useEffect(() => {
    api.instruments().then(setInstruments).catch(() => undefined);
  }, []);

  // Fills change which orders are still open; the read model catches up within a few ms.
  useEffect(() => {
    const timer = setTimeout(refreshOrders, 300);
    return () => clearTimeout(timer);
  }, [refreshOrders, tradeCount]);

  async function handleCancel(id: string) {
    try {
      await api.cancelOrder(id);
    } catch (err) {
      console.error(err);
    }
    refreshOrders();
  }

  const instrument = instruments.find((i) => i.symbol === symbol);
  const priceDigits = instrument ? decimalsOf(instrument.tickSize) : 2;

  return (
    <div className="mx-auto min-h-screen max-w-7xl px-4 py-8 md:px-6">
      <Header connected={connected} />

      <div className="grid gap-4 lg:grid-cols-12">
        <div className="space-y-4 lg:col-span-4">
          <OrderForm
            instruments={instruments}
            symbol={symbol}
            onSymbolChange={setSymbol}
            onOrderPlaced={() => setTimeout(refreshOrders, 300)}
          />
          <OpenOrdersPanel orders={openOrders} priceDigits={priceDigits} onCancel={handleCancel} />
        </div>

        <div className="lg:col-span-5">
          <OrderBookPanel book={book} priceDigits={priceDigits} />
        </div>

        <div className="lg:col-span-3">
          <TradeFeed trades={trades} priceDigits={priceDigits} />
        </div>
      </div>

      <Footer />
    </div>
  );
}
