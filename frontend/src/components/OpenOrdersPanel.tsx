import { formatPrice, formatQty, formatTime } from "@/lib/api";
import type { Order } from "@/lib/types";
import { Panel } from "./Panel";

interface OpenOrdersProps {
  orders: Order[];
  priceDigits: number;
  onCancel: (id: string) => void;
}

export function OpenOrdersPanel({ orders, priceDigits, onCancel }: OpenOrdersProps) {
  return (
    <Panel title="Open Orders" subtitle="Your resting orders, from the read model">
      {orders.length === 0 ? (
        <p className="text-sm text-gray-500">No open orders.</p>
      ) : (
        <ul className="space-y-2">
          {orders.map((order) => (
            <li
              key={order.id}
              className="flex items-center justify-between gap-2 rounded-lg border border-surface-border px-3 py-2 font-mono text-xs"
            >
              <div>
                <span className={order.side === "BUY" ? "text-bid" : "text-ask"}>{order.side}</span>
                <span className="ml-2 text-gray-400">
                  {formatQty(order.remainingQuantity)} @ {formatPrice(order.price, priceDigits)}
                </span>
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
