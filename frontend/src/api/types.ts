export interface Instrument {
  symbol: string
  name: string
  instrumentToken: number
  exchange: string
  instrumentType?: 'EQ' | 'INDEX'
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


// ---------------------------------------------------------------------------
// Screener universes, quotes
// ---------------------------------------------------------------------------

export interface UniverseInfo {
  code: string
  label: string
  size: number
}

export interface Quote {
  symbol: string
  name: string
  type: 'EQ' | 'INDEX'
  ltp: number | null
  prevClose: number | null
  change: number | null
  changePct: number | null
  time: number | null // epoch seconds
}

// ---------------------------------------------------------------------------
// Chart presentation, drawings, saved layout
// ---------------------------------------------------------------------------

export const CHART_TYPES = ['candles', 'heikin', 'bars', 'line', 'area'] as const
export type ChartType = (typeof CHART_TYPES)[number]

export type DrawingTool = 'none' | 'hline' | 'trend' | 'fib'

export type Drawing =
  | { id: string; type: 'hline'; price: number }
  | { id: string; type: 'trend'; t1: number; p1: number; t2: number; p2: number }
  | { id: string; type: 'fib'; t1: number; p1: number; t2: number; p2: number }

export interface ChartLayout {
  symbol: string
  timeframe: Timeframe
  indicators: IndicatorSpec[]
  chartType: ChartType
  watchlist?: string
}

// ---------------------------------------------------------------------------
// Alerts
// ---------------------------------------------------------------------------

export type AlertTriggerMode = 'ONCE' | 'EVERY_BAR'
export type AlertStatus = 'ACTIVE' | 'TRIGGERED' | 'PAUSED'

export interface AlertRequest {
  name?: string
  symbol: string
  timeframe: string
  expression: string
  triggerMode: AlertTriggerMode
}

export interface Alert extends AlertRequest {
  id: number
  name: string
  status: AlertStatus
  createdAt: number
  lastTriggeredAt: number | null
  triggerCount: number
}

export interface AlertEvent {
  id: number
  alertId: number
  alertName: string
  symbol: string
  expression: string
  price: number
  barTime: number // epoch ms
  triggeredAt: number // epoch ms
}

// ---------------------------------------------------------------------------
// Backtest
// ---------------------------------------------------------------------------

export type BacktestDirection = 'LONG' | 'SHORT'

export interface BacktestRequest {
  symbol: string
  timeframe: string
  from?: number
  to?: number
  entry: string
  exit?: string
  direction: BacktestDirection
  stopLossPct?: number
  takeProfitPct?: number
  initialCapital?: number
  positionSizePct?: number
  commissionPct?: number
  slippagePct?: number
}

export interface BacktestTrade {
  side: BacktestDirection
  entryTime: number
  entryPrice: number
  exitTime: number
  exitPrice: number
  quantity: number
  pnl: number
  pnlPct: number
  barsHeld: number
  exitReason: 'SIGNAL' | 'STOP_LOSS' | 'TAKE_PROFIT' | 'END_OF_DATA'
}

export interface BacktestMetrics {
  initialCapital: number
  finalEquity: number
  netProfit: number
  totalReturnPct: number
  buyHoldReturnPct: number
  trades: number
  wins: number
  losses: number
  winRatePct: number
  avgTradePct: number
  avgWinPct: number
  avgLossPct: number
  profitFactor: number | null
  maxDrawdownPct: number
  sharpe: number
  exposurePct: number
  avgBarsHeld: number
  commissionPaid: number
  bars: number
}

/** Strategy state at the latest bar: ENTRY/EXIT = act at the next open; IN_POSITION = holding. */
export type SignalState = 'ENTRY' | 'EXIT' | 'IN_POSITION' | 'NONE'

export interface BacktestLatest {
  time: number // epoch seconds of the latest bar
  close: number
  signal: SignalState
  positionSince: number | null
  positionEntryPrice: number | null
  openPnlPct: number | null
}

export interface BacktestResult {
  symbol: string
  timeframe: string
  metrics: BacktestMetrics
  trades: BacktestTrade[]
  equity: { time: number; equity: number; drawdownPct: number }[]
  latest: BacktestLatest
}

export type ScanSort = 'TOTAL_RETURN' | 'NET_PROFIT' | 'WIN_RATE' | 'PROFIT_FACTOR' | 'SHARPE' | 'MAX_DRAWDOWN' | 'TRADES'

export interface BacktestScanRequest {
  universe: Universe
  strategy: Omit<BacktestRequest, 'symbol'>
  sortBy?: ScanSort
  minTrades?: number
  minWinRatePct?: number
  minProfitFactor?: number
  onlySignals?: boolean
  limit?: number
}

export interface BacktestScanRow {
  symbol: string
  name: string
  latest: BacktestLatest
  metrics: BacktestMetrics
}

export interface BacktestScanResult {
  timeframe: string
  scanned: number
  evaluated: number
  matched: number
  skipped: number
  truncated: boolean
  dataAsOf: number | null
  durationMs: number
  rows: BacktestScanRow[]
  errors: string[]
}

// ---------------------------------------------------------------------------
// Journal & portfolio report
// ---------------------------------------------------------------------------

export interface JournalEntry {
  id: number
  orderId: string | null
  broker: string
  symbol: string
  exchange: string
  side: TransactionType
  product: ProductType
  quantity: number
  price: number
  executedAt: number
  tag: string | null
  strategy: string | null
  notes: string | null
  source: 'APP' | 'SYNC' | 'MANUAL'
}

export interface ManualJournalEntry {
  symbol: string
  side: TransactionType
  product: ProductType
  quantity: number
  price: number
  executedAt?: number
  strategy?: string
  notes?: string
}

export type TaxCategory = 'INTRADAY' | 'STCG' | 'LTCG'

export interface RealizedTrade {
  symbol: string
  side: 'LONG' | 'SHORT'
  quantity: number
  openTime: number
  openPrice: number
  closeTime: number
  closePrice: number
  grossPnl: number
  charges: number
  netPnl: number
  holdingDays: number
  category: TaxCategory
}

export interface PnlBucket {
  key: string
  trades: number
  wins: number
  turnover: number
  grossPnl: number
  charges: number
  netPnl: number
}

export interface FinancialYearTax {
  year: string
  intradayNetPnl: number
  stcg: number
  ltcg: number
  taxableStcg: number
  taxableLtcg: number
  estimatedStcgTax: number
  estimatedLtcgTax: number
}

export interface PortfolioReport {
  summary: {
    trades: number
    wins: number
    losses: number
    winRatePct: number
    turnover: number
    grossPnl: number
    charges: number
    netPnl: number
  }
  byCategory: PnlBucket[]
  bySymbol: PnlBucket[]
  byFinancialYear: FinancialYearTax[]
  daily: { date: string; netPnl: number; cumulative: number }[]
  realized: RealizedTrade[]
  openLots: { symbol: string; quantity: number; averagePrice: number; since: number }[]
  assumptions: string[]
}
