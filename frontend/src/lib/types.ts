export type OrderSide = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type TimeInForce = "GTC" | "IOC" | "FOK";
export type OrderStatus = "OPEN" | "PARTIAL" | "FILLED" | "CANCELLED" | "REJECTED";

export interface Instrument {
  symbol: string;
  tickSize: number;
  lotSize: number;
  minQuantity: number;
  maxQuantity: number;
}

export interface Order {
  id: string;
  symbol: string;
  side: OrderSide | null;
  type: OrderType | null;
  timeInForce: TimeInForce | null;
  postOnly: boolean;
  price: number | null;
  quantity: number | null;
  filledQuantity: number | null;
  remainingQuantity: number | null;
  status: OrderStatus;
  rejectReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PriceLevel {
  price: number;
  quantity: number;
}

export interface OrderBook {
  symbol: string;
  sequence: number;
  bids: PriceLevel[];
  asks: PriceLevel[];
}

export interface Trade {
  id: string;
  symbol: string;
  sequence: number;
  price: number;
  quantity: number;
  takerSide: OrderSide;
  executedAt: string;
}

export interface PlaceOrderRequest {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  timeInForce?: TimeInForce;
  postOnly?: boolean;
  price?: number;
  quantity: number;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  fields?: Record<string, string>;
}

/** Events on GET /api/v1/stream/{symbol}. */
export type StreamSnapshot = OrderBook;

export interface StreamBookUpdate {
  symbol: string;
  sequence: number;
  levels: { side: OrderSide; price: number; quantity: number }[];
}

export interface StreamTrade {
  id: string;
  symbol: string;
  sequence: number;
  price: number;
  quantity: number;
  takerSide: OrderSide;
  executedAt: string;
}
