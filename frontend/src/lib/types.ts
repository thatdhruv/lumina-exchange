export type OrderSide = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type OrderStatus = "OPEN" | "FILLED" | "CANCELLED" | "PARTIAL";

export interface Order {
  id: string;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  price: number;
  quantity: number;
  status: OrderStatus;
  createdAt: string;
}

export interface PriceLevel {
  price: number;
  quantity: number;
}

export interface OrderBook {
  symbol: string;
  bids: PriceLevel[];
  asks: PriceLevel[];
}

export interface Trade {
  id: string;
  buyOrderId: string;
  sellOrderId: string;
  symbol: string;
  price: number;
  quantity: number;
  executedAt: string;
}

export interface PlaceOrderRequest {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  price?: number;
  quantity: number;
}

export interface HealthResponse {
  status: string;
}
