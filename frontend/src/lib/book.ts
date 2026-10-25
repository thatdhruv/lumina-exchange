import type { OrderBook, PriceLevel, StreamBookUpdate, StreamSnapshot } from "./types";

export const EMPTY_BOOK: OrderBook = { symbol: "", sequence: 0, bids: [], asks: [] };

/**
 * A snapshot comes from the read model, which can lag the live stream. Only take it if it is at
 * least as new as what we already show; otherwise a late snapshot would roll the book back.
 */
export function applySnapshot(book: OrderBook, snapshot: StreamSnapshot): OrderBook {
  if (book.symbol === snapshot.symbol && snapshot.sequence < book.sequence) {
    return book;
  }
  return {
    symbol: snapshot.symbol,
    sequence: snapshot.sequence,
    bids: snapshot.bids,
    asks: snapshot.asks,
  };
}

/** Level updates carry absolute quantities, so applying one twice is harmless. */
export function applyUpdate(book: OrderBook, update: StreamBookUpdate, depth = 20): OrderBook {
  if (update.symbol !== book.symbol || update.sequence <= book.sequence) {
    return book;
  }
  let bids = book.bids;
  let asks = book.asks;
  for (const level of update.levels) {
    if (level.side === "BUY") {
      bids = upsert(bids, level.price, level.quantity, true);
    } else {
      asks = upsert(asks, level.price, level.quantity, false);
    }
  }
  return {
    symbol: book.symbol,
    sequence: update.sequence,
    bids: bids.slice(0, depth),
    asks: asks.slice(0, depth),
  };
}

function upsert(levels: PriceLevel[], price: number, quantity: number, descending: boolean): PriceLevel[] {
  const without = levels.filter((l) => l.price !== price);
  if (quantity === 0) {
    return without;
  }
  without.push({ price, quantity });
  without.sort((a, b) => (descending ? b.price - a.price : a.price - b.price));
  return without;
}
