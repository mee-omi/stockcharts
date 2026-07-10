export interface Instrument {
  symbol: string
  name: string
  instrumentToken: number
  exchange: string
}

export interface Candle {
  time: number // epoch seconds (UTC)
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface IndicatorPoint {
  time: number
  value: number | null
}

export interface IndicatorLine {
  name: string
  points: IndicatorPoint[]
}

export interface IndicatorResult {
  key: string
  type: string
  overlay: boolean
  lines: IndicatorLine[]
}

export interface ApplyResponse {
  symbol: string
  timeframe: string
  candles: Candle[]
  indicators: IndicatorResult[]
}

export interface ParamDef {
  name: string
  defaultValue: number
  min: number
  max: number
}

export interface CatalogEntry {
  code: string
  label: string
  overlay: boolean
  custom: boolean
  params: ParamDef[]
  outputs: string[]
}

export interface CustomIndicator {
  name: string
  expression: string
  overlay: boolean
  description?: string
}

export interface ValidationResult {
  valid: boolean
  error?: string
  variables?: string[]
  customReferences?: string[]
}

export interface Watchlist {
  name: string
  symbols: string[]
}

export interface DataSourceInfo {
  active: string
  available: string[]
}

export type UniverseType = 'index' | 'list' | 'watchlist' | 'all'

export interface Universe {
  type: UniverseType
  value?: string
}

export interface ScreenerColumn {
  label: string
  expression: string
}

export interface ScreenerRequest {
  expression: string
  timeframe: string
  universe: Universe
  columns?: ScreenerColumn[]
  limit?: number
}

export interface ScreenerMatch {
  symbol: string
  name: string
  close: number
  columns: Record<string, number | null>
}

export interface ScreenerResult {
  scanned: number
  matched: number
  truncated: boolean
  matches: ScreenerMatch[]
}

export interface ScreenerDef {
  name: string
  expression: string
  universe: Universe
  timeframe: string
}

export interface IndicatorSpec {
  type: string
  params: Record<string, number>
}

export interface LiveUpdate {
  symbol: string
  time: number
  open: number
  high: number
  low: number
  close: number
  volume: number
  ltp: number
}

export const TIMEFRAMES = ['1m', '5m', '15m', '30m', '1h', '1d'] as const
export type Timeframe = (typeof TIMEFRAMES)[number]

// ---------------------------------------------------------------------------
// Trading types
// ---------------------------------------------------------------------------

export type OrderType = 'MARKET' | 'LIMIT' | 'SL' | 'SL-M'
export type TransactionType = 'BUY' | 'SELL'
export type ProductType = 'CNC' | 'MIS' | 'NRML'
export type OrderStatus = 'PENDING' | 'OPEN' | 'COMPLETE' | 'CANCELLED' | 'REJECTED'

export interface PlaceOrderRequest {
  symbol: string
  exchange: string
  transaction_type: TransactionType
  order_type: OrderType
  product: ProductType
  quantity: number
  price?: number
  trigger_price?: number
  tag?: string
}

export interface ModifyOrderRequest {
  quantity?: number
  price?: number
  trigger_price?: number
  order_type?: OrderType
}

export interface Order {
  order_id: string
  symbol: string
  exchange: string
  transaction_type: TransactionType
  order_type: OrderType
  product: ProductType
  quantity: number
  price: number
  trigger_price?: number
  status: OrderStatus
  filled_quantity: number
  average_price: number
  timestamp: number
  message: string
}

export interface Position {
  symbol: string
  exchange: string
  product: ProductType
  quantity: number
  buy_quantity: number
  sell_quantity: number
  average_price: number
  last_price: number
  pnl: number
  day_change: number
}

export interface Positions {
  net: Position[]
  day: Position[]
}

export interface Holding {
  symbol: string
  exchange: string
  quantity: number
  average_price: number
  last_price: number
  pnl: number
  pnl_percent: number
}

export interface Funds {
  available_cash: number
  used_margin: number
  available_margin: number
  total_balance: number
}

export interface TradingBrokerInfo {
  active: string
  available: string[]
}

export interface SetBrokerRequest {
  broker: string
  apiKey?: string
  accessToken?: string
  clientId?: string
  totpSecret?: string
}

/** A single executed trade, used to draw BUY/SELL arrows on the chart. */
export interface TradeMarker {
  time: number          // epoch seconds (same units as Candle.time)
  side: 'BUY' | 'SELL'
  price: number
  quantity: number
  orderId?: string
}

