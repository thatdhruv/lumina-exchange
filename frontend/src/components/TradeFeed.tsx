import { formatPrice, formatQty, formatTime } from "@/lib/api";
import type { Trade } from "@/lib/types";
import { Panel } from "./Panel";

interface TradeFeedProps {
  trades: Trade[];
  priceDigits: number;
}

export function TradeFeed({ trades, priceDigits }: TradeFeedProps) {
  return (
    <Panel title="Recent Trades" subtitle="Streamed as the engine matches" className="min-h-[320px]">
      {trades.length === 0 ? (
        <p className="text-sm text-gray-500">No trades yet. Place crossing orders to see them here.</p>
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
                  <td className={`py-2 pr-3 ${trade.takerSide === "BUY" ? "text-bid" : "text-ask"}`}>
                    {formatPrice(trade.price, priceDigits)}
                  </td>
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
