import { formatPrice, formatQty } from "@/lib/api";
import type { OrderBook } from "@/lib/types";
import { Panel } from "./Panel";

const ROWS = 10;

interface OrderBookPanelProps {
  book: OrderBook;
  priceDigits: number;
}

export function OrderBookPanel({ book, priceDigits }: OrderBookPanelProps) {
  const maxQty = Math.max(...book.bids.map((l) => l.quantity), ...book.asks.map((l) => l.quantity), 1);
  const bestBid = book.bids[0]?.price;
  const bestAsk = book.asks[0]?.price;
  const spread = bestBid !== undefined && bestAsk !== undefined ? bestAsk - bestBid : null;

  return (
    <Panel
      title="Order Book"
      subtitle={`${book.symbol} · seq ${book.sequence}${spread !== null ? ` · spread ${formatPrice(spread, priceDigits)}` : ""}`}
      className="min-h-[420px]"
    >
      <div className="grid grid-cols-2 gap-4 font-mono text-xs uppercase tracking-wider text-gray-500">
        <span>Bids</span>
        <span className="text-right">Asks</span>
      </div>
      <div className="mt-2 space-y-1">
        {Array.from({ length: ROWS }).map((_, i) => {
          const bid = book.bids[i];
          const ask = book.asks[i];
          return (
            <div key={i} className="grid grid-cols-2 gap-2 py-0.5 font-mono text-sm">
              <div className="relative flex justify-between pr-2">
                {bid && (
                  <div
                    className="absolute inset-y-0 right-0 bg-bid/10"
                    style={{ width: `${(bid.quantity / maxQty) * 100}%` }}
                  />
                )}
                <span className="relative z-10 text-bid">{bid ? formatPrice(bid.price, priceDigits) : "—"}</span>
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
                <span className="relative z-10 text-ask">{ask ? formatPrice(ask.price, priceDigits) : "—"}</span>
              </div>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}
