import axios from 'axios'
import type {
  Alert,
  AlertEvent,
  AlertRequest,
  AlertStatus,
  AuthStatus,
  ApplyResponse,
  BacktestRequest,
  BacktestResult,
  BacktestScanRequest,
  BacktestScanResult,
  Candle,
  CatalogEntry,
  CustomIndicator,
  DataSourceInfo,
  Funds,
  HistorySyncInfo,
  Holding,
  IndicatorSpec,
  Instrument,
  JournalEntry,
  ManualJournalEntry,
  MarketStatus,
  PortfolioReport,
  Quote,
  ModifyOrderRequest,
  Order,
  OrderPreview,
  PaperAccount,
  PlaceOrderRequest,
  RiskSettings,
  RiskState,
  Positions,
  ScreenerDef,
  ScreenerRequest,
  ScreenerResult,
  SetBrokerRequest,
  TradingBrokerInfo,
  UniverseInfo,
  ValidationResult,
  Watchlist,
} from './types'

// Same-origin in prod (nginx proxies /api); Vite proxy handles dev.
const api = axios.create({ baseURL: '/api/v1', withCredentials: true })

/** Fired when any API call answers 401, i.e. the session expired or was revoked. */
export const AUTH_REQUIRED_EVENT = 'stockcharts:auth-required'

api.interceptors.response.use(undefined, (err) => {
  const url: string = err?.config?.url ?? ''
  if (err?.response?.status === 401 && !url.startsWith('/auth/')) {
    window.dispatchEvent(new Event(AUTH_REQUIRED_EVENT))
  }
  return Promise.reject(err)
})

/** Human-readable message from an API error ({error, violations} or FastAPI {detail}). */
export function apiErrorMessage(e: any, fallback = 'Request failed'): string {
  const d = e?.response?.data
  if (d && Array.isArray(d.violations) && d.violations.length) return d.violations.join(' · ')
  return d?.error ?? d?.detail ?? e?.message ?? fallback
}

export async function getMarketStatus(): Promise<MarketStatus> {
  const { data } = await api.get<MarketStatus>('/market/status')
  return data
}

// ---- paper trading ----
export async function getPaperAccount(): Promise<PaperAccount> {
  const { data } = await api.get<PaperAccount>('/paper')
  return data
}

export async function resetPaperAccount(startingCash?: number): Promise<PaperAccount> {
  const { data } = await api.post<PaperAccount>('/paper/reset', { startingCash })
  return data
}

// ---- auth ----
export async function getAuthStatus(): Promise<AuthStatus> {
  const { data } = await api.get<AuthStatus>('/auth/status')
  return data
}

export async function login(username: string, password: string): Promise<AuthStatus> {
  const { data } = await api.post<AuthStatus>('/auth/login', { username, password })
  return data
}

export async function setupAccount(username: string, password: string): Promise<AuthStatus> {
  const { data } = await api.post<AuthStatus>('/auth/setup', { username, password })
  return data
}

export async function logout(): Promise<void> {
  await api.post('/auth/logout')
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<AuthStatus> {
  const { data } = await api.post<AuthStatus>('/auth/password', { currentPassword, newPassword })
  return data
}

// ---- risk ----
export async function getRiskState(): Promise<RiskState> {
  const { data } = await api.get<RiskState>('/risk')
  return data
}

export async function updateRiskSettings(settings: RiskSettings): Promise<RiskState> {
  const { data } = await api.put<RiskState>('/risk', settings)
  return data
}

export async function setKillSwitch(enabled: boolean, cancelOpenOrders: boolean)
  : Promise<{ state: RiskState; cancelled: string[]; errors: string[] }> {
  const { data } = await api.post('/risk/kill-switch', { enabled, cancelOpenOrders })
  return data
}

export async function previewOrder(req: PlaceOrderRequest): Promise<OrderPreview> {
  const { data } = await api.post<OrderPreview>('/trading/orders/preview', req)
  return data
}

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
  range?: { from?: number; to?: number },
): Promise<ApplyResponse> {
  const { data } = await api.post<ApplyResponse>('/indicators/apply', {
    symbol,
    timeframe,
    indicators,
    from: range?.from,
    to: range?.to,
  })
  return data
}

/** Raw candles (epoch-ms range), used for the compare overlay. */
export async function getCandles(
  symbol: string,
  timeframe: string,
  range?: { from?: number; to?: number },
): Promise<Candle[]> {
  const { data } = await api.get<Candle[]>(`/candles/${encodeURIComponent(symbol)}`, {
    params: { timeframe, from: range?.from, to: range?.to },
  })
  return data
}

// ---- quotes & universes ----
export async function getQuotes(symbols: string[]): Promise<Quote[]> {
  if (!symbols.length) return []
  const { data } = await api.get<Quote[]>('/quotes', { params: { symbols: symbols.join(',') } })
  return data
}

export async function listUniverses(): Promise<UniverseInfo[]> {
  const { data } = await api.get<UniverseInfo[]>('/screener/universes')
  return data
}

// ---- saved UI state (layout, drawings) ----
export async function getUiState<T>(key: string): Promise<T | null> {
  try {
    const { data } = await api.get<T>(`/ui-state/${encodeURIComponent(key)}`)
    return data
  } catch (e: any) {
    if (e?.response?.status === 404) return null
    throw e
  }
}

export async function putUiState<T>(key: string, value: T): Promise<void> {
  await api.put(`/ui-state/${encodeURIComponent(key)}`, value)
}

// ---- alerts ----
export async function listAlerts(): Promise<Alert[]> {
  const { data } = await api.get<Alert[]>('/alerts')
  return data
}

export async function createAlert(req: AlertRequest): Promise<Alert> {
  const { data } = await api.post<Alert>('/alerts', req)
  return data
}

export async function setAlertStatus(id: number, status: AlertStatus): Promise<Alert> {
  const { data } = await api.post<Alert>(`/alerts/${id}/status`, { status })
  return data
}

export async function deleteAlert(id: number): Promise<void> {
  await api.delete(`/alerts/${id}`)
}

export async function listAlertEvents(limit = 50): Promise<AlertEvent[]> {
  const { data } = await api.get<AlertEvent[]>('/alerts/events', { params: { limit } })
  return data
}

// ---- backtest ----
export async function runBacktest(req: BacktestRequest): Promise<BacktestResult> {
  const { data } = await api.post<BacktestResult>('/backtest', req)
  return data
}

/** One strategy over a whole universe; can take a while on cold caches. */
export async function runBacktestScan(req: BacktestScanRequest): Promise<BacktestScanResult> {
  const { data } = await api.post<BacktestScanResult>('/backtest/scan', req, { timeout: 300_000 })
  return data
}

// ---- journal & portfolio ----
export async function listJournal(): Promise<JournalEntry[]> {
  const { data } = await api.get<JournalEntry[]>('/journal')
  return data
}

export async function addJournalEntry(entry: ManualJournalEntry): Promise<JournalEntry> {
  const { data } = await api.post<JournalEntry>('/journal', entry)
  return data
}

export async function annotateJournalEntry(
  id: number,
  a: { strategy?: string | null; notes?: string | null },
): Promise<JournalEntry> {
  const { data } = await api.put<JournalEntry>(`/journal/${id}`, a)
  return data
}

export async function deleteJournalEntry(id: number): Promise<void> {
  await api.delete(`/journal/${id}`)
}

export async function syncJournal(): Promise<number> {
  const { data } = await api.post<{ added: number }>('/journal/sync')
  return data.added
}

export async function getPortfolioReport(params: { from?: number; to?: number; broker?: string }): Promise<PortfolioReport> {
  const { data } = await api.get<PortfolioReport>('/portfolio/report', { params })
  return data
}

export function portfolioCsvUrl(params: { from?: number; to?: number; broker?: string }): string {
  const q = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => v !== undefined && v !== '' && q.set(k, String(v)))
  const qs = q.toString()
  return `/api/v1/portfolio/report.csv${qs ? `?${qs}` : ''}`
}

/** Pull the most useful message out of an API error (Spring {error}, FastAPI {detail}, or 429). */
export function apiError(e: any, fallback: string): string {
  const d = e?.response?.data
  if (e?.response?.status === 429) return 'Rate limited — please wait a moment and retry'
  return d?.error ?? d?.detail ?? d?.message ?? fallback
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

// ---- history sync ----
export async function getHistorySync(): Promise<HistorySyncInfo> {
  const { data } = await api.get<HistorySyncInfo>('/history-sync')
  return data
}

export async function runHistorySync(): Promise<void> {
  await api.post('/history-sync/run', undefined, { validateStatus: (s) => s === 202 || s === 409 })
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
export async function placeOrder(req: PlaceOrderRequest, confirmationToken?: string | null): Promise<Order> {
  const headers = confirmationToken ? { 'X-Order-Confirmation': confirmationToken } : undefined
  const { data } = await api.post<Order>('/trading/orders', req, { headers })
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
