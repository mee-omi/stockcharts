import axios from 'axios'
import type {
  ApplyResponse,
  CatalogEntry,
  CustomIndicator,
  DataSourceInfo,
  Funds,
  Holding,
  IndicatorSpec,
  Instrument,
  ModifyOrderRequest,
  Order,
  PlaceOrderRequest,
  Positions,
  ScreenerDef,
  ScreenerRequest,
  ScreenerResult,
  SetBrokerRequest,
  TradingBrokerInfo,
  ValidationResult,
  Watchlist,
} from './types'

// Same-origin in prod (nginx proxies /api); Vite proxy handles dev.
const api = axios.create({ baseURL: '/api/v1' })

export async function searchInstruments(query: string): Promise<Instrument[]> {
  const { data } = await api.get<Instrument[]>('/instruments', {
    params: query ? { query } : {},
  })
  return data
}

export async function getIndicatorCatalog(): Promise<CatalogEntry[]> {
  const { data } = await api.get<CatalogEntry[]>('/indicators')
  return data
}

/**
 * Single call that returns candles + any requested indicator series, time-aligned.
 * Passing an empty indicator list just returns the candles.
 */
export async function applyIndicators(
  symbol: string,
  timeframe: string,
  indicators: IndicatorSpec[],
): Promise<ApplyResponse> {
  const { data } = await api.post<ApplyResponse>('/indicators/apply', {
    symbol,
    timeframe,
    indicators,
  })
  return data
}

// ---- custom indicators ----
export async function listCustomIndicators(): Promise<CustomIndicator[]> {
  const { data } = await api.get<CustomIndicator[]>('/custom-indicators')
  return data
}

export async function createCustomIndicator(ci: CustomIndicator): Promise<CustomIndicator> {
  const { data } = await api.post<CustomIndicator>('/custom-indicators', ci)
  return data
}

export async function deleteCustomIndicator(name: string): Promise<void> {
  await api.delete(`/custom-indicators/${encodeURIComponent(name)}`)
}

export async function validateExpression(expression: string): Promise<ValidationResult> {
  const { data } = await api.post<ValidationResult>('/custom-indicators/validate', { expression })
  return data
}

// ---- watchlists ----
export async function listWatchlists(): Promise<Watchlist[]> {
  const { data } = await api.get<Watchlist[]>('/watchlists')
  return data
}

export async function saveWatchlist(wl: Watchlist): Promise<Watchlist> {
  const { data } = await api.post<Watchlist>('/watchlists', wl)
  return data
}

export async function deleteWatchlist(name: string): Promise<void> {
  await api.delete(`/watchlists/${encodeURIComponent(name)}`)
}

// ---- screener ----
export async function runScreener(req: ScreenerRequest): Promise<ScreenerResult> {
  const { data } = await api.post<ScreenerResult>('/screener/run', req)
  return data
}

export async function listScreeners(): Promise<ScreenerDef[]> {
  const { data } = await api.get<ScreenerDef[]>('/screener')
  return data
}

export async function saveScreener(def: ScreenerDef): Promise<ScreenerDef> {
  const { data } = await api.post<ScreenerDef>('/screener', def)
  return data
}

export async function deleteScreener(name: string): Promise<void> {
  await api.delete(`/screener/${encodeURIComponent(name)}`)
}

// ---- data source ----
export async function getDataSource(): Promise<DataSourceInfo> {
  const { data } = await api.get<DataSourceInfo>('/datasource')
  return data
}

export async function setDataSource(provider: string, enctoken?: string): Promise<DataSourceInfo> {
  const { data } = await api.post<DataSourceInfo>('/datasource', { provider, enctoken })
  return data
}

// ---- trading broker ----
export async function getTradingBroker(): Promise<TradingBrokerInfo> {
  const { data } = await api.get<TradingBrokerInfo>('/broker')
  return data
}

export async function setTradingBroker(req: SetBrokerRequest): Promise<TradingBrokerInfo> {
  const { data } = await api.post<TradingBrokerInfo>('/broker', req)
  return data
}

// ---- orders ----
export async function placeOrder(req: PlaceOrderRequest): Promise<Order> {
  const { data } = await api.post<Order>('/trading/orders', req)
  return data
}

export async function getOrders(): Promise<Order[]> {
  const { data } = await api.get<Order[]>('/trading/orders')
  return data
}

export async function cancelOrder(orderId: string): Promise<void> {
  await api.delete(`/trading/orders/${encodeURIComponent(orderId)}`)
}

export async function modifyOrder(orderId: string, req: ModifyOrderRequest): Promise<Order> {
  const { data } = await api.put<Order>(`/trading/orders/${encodeURIComponent(orderId)}`, req)
  return data
}

// ---- positions & holdings ----
export async function getPositions(): Promise<Positions> {
  const { data } = await api.get<Positions>('/trading/positions')
  return data
}

export async function getHoldings(): Promise<Holding[]> {
  const { data } = await api.get<Holding[]>('/trading/holdings')
  return data
}

// ---- funds ----
export async function getFunds(): Promise<Funds> {
  const { data } = await api.get<Funds>('/trading/funds')
  return data
}
