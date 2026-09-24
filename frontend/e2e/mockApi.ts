import type { Page, Route } from '@playwright/test'

/** Deterministic candles ending now, `step` seconds apart. */
export function candles(n: number, step: number, base = 2900) {
  const now = Math.floor(Date.now() / 1000)
  const start = now - (now % step) - (n - 1) * step
  const out = []
  let price = base
  for (let i = 0; i < n; i++) {
    const open = price
    const close = open + Math.sin(i / 6) * 8
    out.push({ time: start + i * step, open, high: Math.max(open, close) + 3, low: Math.min(open, close) - 3, close, volume: 1000 + i })
    price = close
  }
  return out
}

const INSTRUMENTS = [
  { symbol: 'RELIANCE', name: 'Reliance Industries Ltd', instrumentToken: 1, exchange: 'NSE', instrumentType: 'EQ' },
  { symbol: 'TCS', name: 'Tata Consultancy Services Ltd', instrumentToken: 2, exchange: 'NSE', instrumentType: 'EQ' },
  { symbol: 'NIFTY50', name: 'NIFTY 50', instrumentToken: 3, exchange: 'NSE', instrumentType: 'INDEX' },
]

const STEP: Record<string, number> = { '1m': 60, '5m': 300, '15m': 900, '30m': 1800, '1h': 3600, '1d': 86400 }

export interface MockState {
  uiState: Record<string, unknown>
  watchlists: { name: string; symbols: string[] }[]
  alerts: any[]
  requests: { method: string; path: string; body: any }[]
  auth: { authEnabled: boolean; setupRequired: boolean; authenticated: boolean; username: string | null }
  broker: string
  risk: any
}

const DEFAULT_RISK = { killSwitch: false, maxQuantityPerOrder: 1000, maxOrderValue: 200000, maxOrdersPerDay: 50,
  maxDailyLoss: 10000, requireConfirmation: true, applyToPaper: true }

/** Route every /api/v1 call to in-memory fixtures; STOMP (/ws) is blocked. */
export async function mockApi(page: Page, init: Partial<MockState> = {}): Promise<MockState> {
  const state: MockState = {
    uiState: init.uiState ?? {},
    watchlists: init.watchlists ?? [{ name: 'Core', symbols: ['RELIANCE', 'TCS', 'NIFTY50'] }],
    alerts: init.alerts ?? [],
    requests: [],
    auth: init.auth ?? { authEnabled: true, setupRequired: false, authenticated: true, username: 'trader' },
    broker: init.broker ?? 'mock',
    risk: init.risk ?? { ...DEFAULT_RISK },
  }
  // Only the root /ws endpoint: a glob like **/ws/** would also catch Vite's /src/ws/ modules.
  await page.route((url) => url.pathname.startsWith('/ws/') || url.pathname === '/ws', (r) => r.abort())
  await page.route((url) => url.pathname.startsWith('/api/v1/'), async (route: Route) => {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname.replace('/api/v1', '')
    const method = req.method()
    const body = req.postDataJSON?.() ?? null
    state.requests.push({ method, path, body })
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })

    if (path === '/auth/status') return json(state.auth)
    if (path === '/auth/login' || path === '/auth/setup') {
      if (body.password !== 'correct-horse-1') return json({ error: 'Invalid username or password' }, 401)
      state.auth = { ...state.auth, setupRequired: false, authenticated: true, username: body.username }
      return json(state.auth)
    }
    if (path === '/auth/logout') {
      state.auth = { ...state.auth, authenticated: false, username: null }
      return json(state.auth)
    }
    if (state.auth.authEnabled && !state.auth.authenticated) return json({ error: 'Login required' }, 401)
    const riskState = () => ({ settings: state.risk, broker: state.broker, ordersToday: 0, dayPnl: 0, lossLimitReached: false })
    if (path === '/risk' && method === 'PUT') { state.risk = body; return json(riskState()) }
    if (path === '/risk') return json(riskState())
    if (path === '/risk/kill-switch') {
      state.risk = { ...state.risk, killSwitch: body.enabled }
      return json({ state: riskState(), cancelled: body.cancelOpenOrders ? ['o-open'] : [], errors: [] })
    }
    if (path === '/trading/orders/preview') {
      const live = state.broker !== 'mock'
      const violations = state.risk.killSwitch ? ['Kill switch is ON — only orders that reduce an open position are allowed'] : []
      const price = body.price ?? 1000
      return json({ assessment: { broker: state.broker, live, price, estimatedValue: price * body.quantity, reducing: false,
        ordersToday: 0, dayPnl: 0, violations, warnings: [] },
        confirmationRequired: live && !violations.length, confirmationToken: violations.length ? null : 'tok-1',
        expiresAt: new Date(Date.now() + 60_000).toISOString() })
    }
    if (path === '/trading/orders' && method === 'POST') {
      return json({ order_id: 'o-1', symbol: body.symbol, exchange: 'NSE', transaction_type: body.transaction_type,
        order_type: body.order_type, product: body.product, quantity: body.quantity, price: body.price ?? 0,
        trigger_price: null, status: 'OPEN', filled_quantity: 0, average_price: 0, timestamp: Date.now(), tag: '' })
    }
    if (path === '/trading/orders') return json([])
    if (path === '/instruments') {
      const q = (url.searchParams.get('query') ?? '').toLowerCase()
      return json(INSTRUMENTS.filter((i) => !q || i.symbol.toLowerCase().includes(q) || i.name.toLowerCase().includes(q)))
    }
    if (path === '/indicators' && method === 'GET') {
      return json([{ code: 'EMA', label: 'EMA', overlay: true, custom: false, params: [{ name: 'period', defaultValue: 20, min: 1, max: 500 }], outputs: ['value'] }])
    }
    if (path === '/indicators/apply') {
      return json({ symbol: body.symbol, timeframe: body.timeframe, candles: candles(150, STEP[body.timeframe] ?? 60), indicators: [] })
    }
    if (path.startsWith('/candles/')) return json(candles(150, STEP[url.searchParams.get('timeframe') ?? '1m'], 24000))
    if (path === '/datasource') return json({ active: 'mock', available: ['mock', 'zerodha'], status: {} })
    if (path === '/history-sync') {
      return json({ status: { running: false, startedAt: null, finishedAt: null, trigger: null, provider: null, symbols: 0,
        done: 0, candlesStored: 0, failures: 0, failureSamples: [], skippedReason: null }, trackedSymbols: 3,
        lookbackDays: { '1d': 1825, '1m': 30 } })
    }
    if (path === '/broker') return json({ active: state.broker, available: ['mock', 'zerodha'] })
    if (path === '/screener/universes') return json([{ code: 'NIFTY50', label: 'NIFTY 50', size: 50 }])
    if (path.startsWith('/ui-state/')) {
      const key = decodeURIComponent(path.slice('/ui-state/'.length))
      if (method === 'PUT') { state.uiState[key] = body; return json(body) }
      return key in state.uiState ? json(state.uiState[key]) : json({ error: 'not found' }, 404)
    }
    if (path === '/watchlists') {
      if (method === 'POST') {
        state.watchlists = [...state.watchlists.filter((w) => w.name !== body.name), body]
        return json(body)
      }
      return json(state.watchlists)
    }
    if (path === '/quotes') {
      const syms = (url.searchParams.get('symbols') ?? '').split(',')
      return json(INSTRUMENTS.filter((i) => syms.includes(i.symbol)).map((i, k) => ({
        symbol: i.symbol, name: i.name, type: i.instrumentType, ltp: 1000 + k * 100, prevClose: 1000,
        change: k * 100, changePct: k * 10, time: 0,
      })))
    }
    if (path === '/alerts' && method === 'POST') {
      const a = { id: state.alerts.length + 1, name: body.name ?? `${body.symbol}: ${body.expression}`, status: 'ACTIVE',
        createdAt: Date.now(), lastTriggeredAt: null, triggerCount: 0, ...body }
      state.alerts.push(a)
      return json(a)
    }
    if (path === '/alerts') return json(state.alerts)
    if (path === '/alerts/events') return json([])
    if (path === '/backtest') {
      const eq = candles(50, 86400, 100000).map((c) => ({ time: c.time, equity: c.close, drawdownPct: -1 }))
      return json({
        symbol: body.symbol, timeframe: body.timeframe,
        metrics: { initialCapital: 100000, finalEquity: 112345, netProfit: 12345, totalReturnPct: 12.35, buyHoldReturnPct: 5,
          trades: 3, wins: 2, losses: 1, winRatePct: 66.7, avgTradePct: 4, avgWinPct: 7, avgLossPct: -2, profitFactor: 2.5,
          maxDrawdownPct: 6.1, sharpe: 1.4, exposurePct: 40, avgBarsHeld: 5, commissionPaid: 120, bars: 50 },
        trades: [{ side: 'LONG', entryTime: eq[5].time, entryPrice: 100, exitTime: eq[9].time, exitPrice: 110, quantity: 10,
          pnl: 100, pnlPct: 10, barsHeld: 4, exitReason: 'SIGNAL' }],
        equity: eq,
        latest: { time: eq[eq.length - 1].time, close: 112, signal: 'IN_POSITION', positionSince: eq[40].time,
          positionEntryPrice: 105, openPnlPct: 6.67 },
      })
    }
    if (path === '/backtest/scan') {
      const m = (ret: number, trades: number) => ({ initialCapital: 100000, finalEquity: 100000 * (1 + ret / 100),
        netProfit: ret * 1000, totalReturnPct: ret, buyHoldReturnPct: 4, trades, wins: Math.ceil(trades / 2),
        losses: Math.floor(trades / 2), winRatePct: 55, avgTradePct: 1, avgWinPct: 3, avgLossPct: -1,
        profitFactor: 1.8, maxDrawdownPct: 7, sharpe: 1.1, exposurePct: 30, avgBarsHeld: 6, commissionPaid: 50, bars: 750 })
      const latest = (signal: string) => ({ time: Math.floor(Date.now() / 1000), close: 1000, signal,
        positionSince: null, positionEntryPrice: null, openPnlPct: signal === 'IN_POSITION' ? 2.5 : null })
      return json({ timeframe: body.strategy.timeframe, scanned: 3, evaluated: 3, matched: 2, skipped: 0,
        truncated: false, dataAsOf: Math.floor(Date.now() / 1000), durationMs: 1234, errors: [],
        rows: [
          { symbol: 'TCS', name: 'Tata Consultancy Services Ltd', latest: latest('ENTRY'), metrics: m(25, 8) },
          { symbol: 'RELIANCE', name: 'Reliance Industries Ltd', latest: latest('IN_POSITION'), metrics: m(12, 6) },
        ] })
    }
    if (path === '/portfolio/report') {
      return json({
        summary: { trades: 1, wins: 1, losses: 0, winRatePct: 100, turnover: 58000, grossPnl: 500, charges: 20, netPnl: 480 },
        byCategory: [{ key: 'INTRADAY', trades: 1, wins: 1, turnover: 58000, grossPnl: 500, charges: 20, netPnl: 480 }],
        bySymbol: [{ key: 'RELIANCE', trades: 1, wins: 1, turnover: 58000, grossPnl: 500, charges: 20, netPnl: 480 }],
        byFinancialYear: [{ year: 'FY2026-27', intradayNetPnl: 480, stcg: 0, ltcg: 0, taxableStcg: 0, taxableLtcg: 0, estimatedStcgTax: 0, estimatedLtcgTax: 0 }],
        daily: [{ date: '2026-09-24', netPnl: 480, cumulative: 480 }],
        realized: [{ symbol: 'RELIANCE', side: 'LONG', quantity: 10, openTime: Date.now() - 3600_000, openPrice: 2900,
          closeTime: Date.now(), closePrice: 2950, grossPnl: 500, charges: 20, netPnl: 480, holdingDays: 0, category: 'INTRADAY' }],
        openLots: [],
        assumptions: ['Estimates only'],
      })
    }
    if (path === '/journal') return json([])
    if (path === '/custom-indicators') return json([])
    if (path === '/screener') return json([])
    return json({ error: `unmocked ${method} ${path}` }, 404)
  })
  return state
}
