import { describe, expect, it } from "vitest";
import { applySnapshot, applyUpdate } from "./book";
import type { OrderBook } from "./types";

const base: OrderBook = {
  symbol: "BTC-USD",
  sequence: 10,
  bids: [
    { price: 100, quantity: 1 },
    { price: 99, quantity: 2 },
  ],
  asks: [{ price: 101, quantity: 3 }],
};

describe("applyUpdate", () => {
  it("replaces a level with the absolute quantity", () => {
    const next = applyUpdate(base, {
      symbol: "BTC-USD",
      sequence: 11,
      levels: [{ side: "BUY", price: 100, quantity: 5 }],
    });
    expect(next.bids[0]).toEqual({ price: 100, quantity: 5 });
    expect(next.sequence).toBe(11);
  });

  it("removes a level at zero and keeps best-first ordering", () => {
    const next = applyUpdate(base, {
      symbol: "BTC-USD",
      sequence: 11,
      levels: [
        { side: "BUY", price: 100, quantity: 0 },
        { side: "BUY", price: 99.5, quantity: 4 },
        { side: "SELL", price: 100.5, quantity: 1 },
      ],
    });
    expect(next.bids.map((l) => l.price)).toEqual([99.5, 99]);
    expect(next.asks.map((l) => l.price)).toEqual([100.5, 101]);
  });

  it("ignores stale and replayed updates", () => {
    const stale = { symbol: "BTC-USD", sequence: 10, levels: [{ side: "BUY" as const, price: 100, quantity: 9 }] };
    expect(applyUpdate(base, stale)).toBe(base);
  });

  it("ignores updates for another symbol", () => {
    const other = { symbol: "ETH-USD", sequence: 99, levels: [] };
    expect(applyUpdate(base, other)).toBe(base);
  });
});

describe("applySnapshot", () => {
  it("replaces the book when it is at least as new", () => {
    const next = applySnapshot(base, { symbol: "BTC-USD", sequence: 10, bids: [], asks: [] });
    expect(next.bids).toEqual([]);
  });

  it("does not roll the book back to an older, lagging snapshot", () => {
    const next = applySnapshot(base, { symbol: "BTC-USD", sequence: 7, bids: [], asks: [] });
    expect(next).toBe(base);
  });

  it("always accepts a snapshot for a newly selected symbol", () => {
    const next = applySnapshot(base, { symbol: "ETH-USD", sequence: 1, bids: [], asks: [] });
    expect(next.symbol).toBe("ETH-USD");
  });
});
