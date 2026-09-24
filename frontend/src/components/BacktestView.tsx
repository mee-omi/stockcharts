import { useEffect, useMemo, useRef, useState } from 'react'
import { apiError, listUniverses, listWatchlists, runBacktest, runBacktestScan } from '../api/client'
import {
  TIMEFRAMES,
  type BacktestDirection,
  type BacktestRequest,
  type BacktestResult,
  type BacktestScanResult,
  type ScanSort,
  type SignalState,
  type UniverseInfo,
  type UniverseType,
  type Watchlist,
  type Timeframe,
  type TradeMarker,
} from '../api/types'
import { inr, istDate, istDateTime, num, pct, signClass } from '../lib/format'
import EquityChart from './EquityChart'
import StockSearch from './StockSearch'

const PRESETS: { label: string; entry: string; exit: string; direction: BacktestDirection }[] = [
  { label: 'EMA 9/21 crossover', entry: 'crossover(ema(close, 9), ema(close, 21))',
    exit: 'crossunder(ema(close, 9), ema(close, 21))', direction: 'LONG' },
  { label: 'RSI mean reversion (30/55)', entry: 'crossover(rsi(close, 14), 30)',
    exit: 'rsi(close, 14) > 55', direction: 'LONG' },
  { label: 'Bollinger breakout', entry: 'crossover(close, bb_upper(close, 20, 2))',
    exit: 'crossunder(close, bb_middle(close, 20))', direction: 'LONG' },
  { label: 'MACD zero-line short', entry: 'crossunder(macd(close, 12, 26), 0)',
    exit: 'crossover(macd(close, 12, 26), 0)', direction: 'SHORT' },
]

interface Props {
  defaultSymbol?: string
  onShowOnChart: (symbol: string, timeframe: Timeframe, markers: TradeMarker[]) => void
}

function optionalNumber(v: string): number | undefined {
  const n = Number(v)
  return v.trim() === '' || !Number.isFinite(n) ? undefined : n
}

const SORTS: { value: ScanSort; label: string }[] = [
  { value: 'TOTAL_RETURN', label: 'Return' },
  { value: 'PROFIT_FACTOR', label: 'Profit factor' },
  { value: 'WIN_RATE', label: 'Win rate' },
  { value: 'SHARPE', label: 'Sharpe' },
  { value: 'MAX_DRAWDOWN', label: 'Lowest drawdown' },
  { value: 'NET_PROFIT', label: 'Net profit' },
  { value: 'TRADES', label: 'Trades' },
]

const AUTO_RERUN_MINUTES = [0, 5, 15, 60]

export function signalLabel(signal: SignalState, direction: BacktestDirection, openPnlPct?: number | null): string {
  switch (signal) {
    case 'ENTRY': return direction === 'SHORT' ? 'Short at next open' : 'Buy at next open'
    case 'EXIT': return direction === 'SHORT' ? 'Cover at next open' : 'Sell at next open'
    case 'IN_POSITION': return `Holding${openPnlPct !== null && openPnlPct !== undefined ? ` ${pct(openPnlPct)}` : ''}`
    default: return '—'
  }
}

type Mode = 'single' | 'scan'

export default function BacktestView({ defaultSymbol, onShowOnChart }: Props) {
  const [mode, setMode] = useState<Mode>('single')
  const [symbol, setSymbol] = useState(defaultSymbol ?? '')
  const [timeframe, setTimeframe] = useState<Timeframe>('1d')
  const [direction, setDirection] = useState<BacktestDirection>('LONG')
  const [entry, setEntry] = useState(PRESETS[0].entry)
  const [exit, setExit] = useState(PRESETS[0].exit)
  const [stopLoss, setStopLoss] = useState('5')
  const [takeProfit, setTakeProfit] = useState('')
  const [capital, setCapital] = useState('100000')
  const [size, setSize] = useState('100')
  const [commission, setCommission] = useState('0.03')
  const [slippage, setSlippage] = useState('0.02')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [result, setResult] = useState<BacktestResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Scanner state
  const [universeType, setUniverseType] = useState<UniverseType>('index')
  const [universeValue, setUniverseValue] = useState('NIFTY50')
  const [indices, setIndices] = useState<UniverseInfo[]>([])
  const [watchlists, setWatchlists] = useState<Watchlist[]>([])
  const [sortBy, setSortBy] = useState<ScanSort>('TOTAL_RETURN')
  const [minTrades, setMinTrades] = useState('3')
  const [minWinRate, setMinWinRate] = useState('')
  const [minPf, setMinPf] = useState('')
  const [onlySignals, setOnlySignals] = useState(false)
  const [autoMinutes, setAutoMinutes] = useState(0)
  const [scan, setScan] = useState<BacktestScanResult | null>(null)
  const [scanDirection, setScanDirection] = useState<BacktestDirection>('LONG')

  useEffect(() => {
    listUniverses().then(setIndices).catch(() => {})
    listWatchlists().then(setWatchlists).catch(() => {})
  }, [])

  const applyPreset = (i: number) => {
    const p = PRESETS[i]
    if (!p) return
    setEntry(p.entry)
    setExit(p.exit)
    setDirection(p.direction)
  }

  const strategy = (): Omit<BacktestRequest, 'symbol'> => ({
    timeframe, direction, entry, exit: exit.trim() || undefined,
    stopLossPct: optionalNumber(stopLoss), takeProfitPct: optionalNumber(takeProfit),
    initialCapital: optionalNumber(capital), positionSizePct: optionalNumber(size),
    commissionPct: optionalNumber(commission), slippagePct: optionalNumber(slippage),
    from: from ? new Date(from).getTime() : undefined,
    to: to ? new Date(`${to}T23:59:59`).getTime() : undefined,
  })

  const runSingle = async (sym: string) => {
    if (!sym) return setError('Pick a symbol')
    setBusy(true)
    setError(null)
    try {
      setResult(await runBacktest({ symbol: sym, ...strategy() }))
    } catch (e) {
      setError(apiError(e, 'Backtest failed'))
      setResult(null)
    } finally {
      setBusy(false)
    }
  }

  const runScan = async () => {
    if (universeType !== 'all' && !universeValue.trim()) return setError('Choose a universe')
    setBusy(true)
    setError(null)
    try {
      setScan(await runBacktestScan({
        universe: { type: universeType, value: universeType === 'all' ? undefined : universeValue },
        strategy: strategy(),
        sortBy,
        minTrades: optionalNumber(minTrades),
        minWinRatePct: optionalNumber(minWinRate),
        minProfitFactor: optionalNumber(minPf),
        onlySignals,
        limit: 200,
      }))
      setScanDirection(direction)
    } catch (e) {
      setError(apiError(e, 'Scan failed'))
    } finally {
      setBusy(false)
    }
  }

  const run = () => (mode === 'scan' ? runScan() : runSingle(symbol))

  // Optional auto re-run so the scan keeps up with new bars while the page is open.
  const runScanRef = useRef(runScan)
  runScanRef.current = runScan
  useEffect(() => {
    if (mode !== 'scan' || !autoMinutes) return
    const id = setInterval(() => { runScanRef.current() }, autoMinutes * 60_000)
    return () => clearInterval(id)
  }, [mode, autoMinutes])

  const openFromScan = (sym: string) => {
    setMode('single')
    setSymbol(sym)
    runSingle(sym)
  }

  const equity = useMemo(() => result?.equity.map((p) => ({ time: p.time, value: p.equity })) ?? [], [result])
  const drawdown = useMemo(() => result?.equity.map((p) => ({ time: p.time, value: p.drawdownPct })) ?? [], [result])

  const markers = (): TradeMarker[] => (result?.trades ?? []).flatMap((t) => {
    const long = t.side === 'LONG'
    return [
      { time: t.entryTime, side: long ? 'BUY' : 'SELL', price: t.entryPrice, quantity: t.quantity },
      { time: t.exitTime, side: long ? 'SELL' : 'BUY', price: t.exitPrice, quantity: t.quantity },
    ] as TradeMarker[]
  })

  const m = result?.metrics

  return (
    <div className="page">
      <div className="trade-tabs page-tabs" role="tablist">
        <button role="tab" aria-selected={mode === 'single'} className={`trade-tab ${mode === 'single' ? 'active' : ''}`}
                onClick={() => setMode('single')}>Single symbol</button>
        <button role="tab" aria-selected={mode === 'scan'} className={`trade-tab ${mode === 'scan' ? 'active' : ''}`}
                onClick={() => setMode('scan')}>Scan universe</button>
      </div>

      <section className="card">
        <h3>Strategy</h3>
        <div className="form-grid">
          {mode === 'single' ? (
            <label>Symbol
              {symbol ? (
                <span className="compare-chip">
                  <strong>{symbol}</strong>
                  <button onClick={() => setSymbol('')} aria-label="Change symbol">✕</button>
                </span>
              ) : (
                <StockSearch compact placeholder="Symbol…" ariaLabel="Backtest symbol" onSelect={setSymbol} />
              )}
            </label>
          ) : (
            <>
              <label>Universe
                <select value={universeType} aria-label="Universe type" onChange={(e) => {
                  const t = e.target.value as UniverseType
                  setUniverseType(t)
                  setUniverseValue(t === 'index' ? 'NIFTY50' : t === 'watchlist' ? (watchlists[0]?.name ?? '') : '')
                }}>
                  <option value="index">Index</option>
                  <option value="watchlist">Watchlist</option>
                  <option value="list">Custom list</option>
                  <option value="all">All stocks</option>
                </select>
              </label>
              {universeType === 'index' && (
                <label>Index
                  <select value={universeValue} onChange={(e) => setUniverseValue(e.target.value)} aria-label="Index">
                    {(indices.length ? indices : [{ code: 'NIFTY50', label: 'NIFTY 50', size: 50 }]).map((i) => (
                      <option key={i.code} value={i.code}>{i.label} ({i.size})</option>
                    ))}
                  </select>
                </label>
              )}
              {universeType === 'watchlist' && (
                <label>Watchlist
                  <select value={universeValue} onChange={(e) => setUniverseValue(e.target.value)} aria-label="Watchlist">
                    {!watchlists.length && <option value="">No watchlists</option>}
                    {watchlists.map((w) => <option key={w.name} value={w.name}>{w.name} ({w.symbols.length})</option>)}
                  </select>
                </label>
              )}
              {universeType === 'list' && (
                <label>Symbols
                  <input value={universeValue} onChange={(e) => setUniverseValue(e.target.value.toUpperCase())}
                         placeholder="RELIANCE, TCS, INFY" aria-label="Symbols" />
                </label>
              )}
            </>
          )}
          <label>Timeframe
            <select value={timeframe} onChange={(e) => setTimeframe(e.target.value as Timeframe)}>
              {TIMEFRAMES.map((tf) => <option key={tf} value={tf}>{tf}</option>)}
            </select>
          </label>
          <label>Direction
            <select value={direction} onChange={(e) => setDirection(e.target.value as BacktestDirection)}>
              <option value="LONG">Long</option>
              <option value="SHORT">Short</option>
            </select>
          </label>
          <label>Preset
            <select defaultValue="0" onChange={(e) => applyPreset(Number(e.target.value))} aria-label="Preset strategy">
              {PRESETS.map((p, i) => <option key={p.label} value={i}>{p.label}</option>)}
            </select>
          </label>
          <label className="span-2">Entry when (at bar close → fill next open)
            <textarea className="cb-expr" rows={2} value={entry} onChange={(e) => setEntry(e.target.value)}
                      aria-label="Entry condition" />
          </label>
          <label className="span-2">Exit when (optional)
            <textarea className="cb-expr" rows={2} value={exit} onChange={(e) => setExit(e.target.value)}
                      aria-label="Exit condition" />
          </label>
          <label>Stop-loss %<input type="number" value={stopLoss} onChange={(e) => setStopLoss(e.target.value)} /></label>
          <label>Take-profit %<input type="number" value={takeProfit} onChange={(e) => setTakeProfit(e.target.value)} placeholder="off" /></label>
          <label>Capital ₹<input type="number" value={capital} onChange={(e) => setCapital(e.target.value)} /></label>
          <label>Position size %<input type="number" value={size} onChange={(e) => setSize(e.target.value)} /></label>
          <label>Commission %/leg<input type="number" value={commission} onChange={(e) => setCommission(e.target.value)} /></label>
          <label>Slippage %/leg<input type="number" value={slippage} onChange={(e) => setSlippage(e.target.value)} /></label>
          <label>From<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>To<input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        </div>
        {mode === 'scan' && (
          <div className="form-grid scan-filters">
            <label>Rank by
              <select value={sortBy} onChange={(e) => setSortBy(e.target.value as ScanSort)} aria-label="Rank by">
                {SORTS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </label>
            <label>Min trades<input type="number" value={minTrades} onChange={(e) => setMinTrades(e.target.value)} /></label>
            <label>Min win rate %<input type="number" value={minWinRate} onChange={(e) => setMinWinRate(e.target.value)} placeholder="any" /></label>
            <label>Min profit factor<input type="number" value={minPf} onChange={(e) => setMinPf(e.target.value)} placeholder="any" /></label>
            <label>Re-run automatically
              <select value={autoMinutes} onChange={(e) => setAutoMinutes(Number(e.target.value))} aria-label="Re-run automatically">
                {AUTO_RERUN_MINUTES.map((n) => <option key={n} value={n}>{n ? `every ${n} min` : 'off'}</option>)}
              </select>
            </label>
            <label className="check">
              <input type="checkbox" checked={onlySignals} onChange={(e) => setOnlySignals(e.target.checked)} />
              Only symbols with an entry/exit signal on the latest bar
            </label>
          </div>
        )}
        <div className="form-actions">
          <button className="run-btn" onClick={run} disabled={busy}>
            {busy ? 'Running…' : mode === 'scan' ? 'Run scan' : 'Run backtest'}
          </button>
          {error && <span className="err">{error}</span>}
          {!from && <span className="muted small">Default range: 3y (1d), 180d (1h), 10d (1m), 30d otherwise.</span>}
        </div>
      </section>

      {mode === 'scan' && scan && (
        <section className="card">
          <div className="card-head">
            <h3>
              {scan.matched} of {scan.evaluated} symbols · {scan.timeframe}
              {scan.skipped > 0 && <span className="muted small"> · {scan.skipped} skipped</span>}
            </h3>
            <span className="muted small">
              Latest bar: {scan.dataAsOf
                ? (scan.timeframe === '1d' ? istDate(scan.dataAsOf * 1000) : `${istDateTime(scan.dataAsOf * 1000)} IST`)
                : '—'} · {(scan.durationMs / 1000).toFixed(1)}s
              {autoMinutes ? ` · re-running every ${autoMinutes} min` : ''}
            </span>
          </div>
          {!scan.rows.length ? (
            <div className="muted">No symbols passed the filters.</div>
          ) : (
            <div className="table-wrap">
              <table className="results-table scan-table">
                <thead>
                  <tr>
                    <th>Symbol</th><th>Latest signal</th><th className="num">Return</th><th className="num">Buy &amp; hold</th>
                    <th className="num">Trades</th><th className="num">Win %</th><th className="num">PF</th>
                    <th className="num">Max DD</th><th className="num">Sharpe</th><th className="num">Last close</th>
                  </tr>
                </thead>
                <tbody>
                  {scan.rows.map((r) => (
                    <tr key={r.symbol} className="result-row" onClick={() => openFromScan(r.symbol)}
                        title="Open the full backtest for this symbol">
                      <td className="sym">{r.symbol}<div className="muted small">{r.name}</div></td>
                      <td><span className={`sig ${r.latest.signal.toLowerCase()}`}>
                        {signalLabel(r.latest.signal, scanDirection, r.latest.openPnlPct)}</span></td>
                      <td className={`num ${signClass(r.metrics.totalReturnPct)}`}>{pct(r.metrics.totalReturnPct)}</td>
                      <td className={`num ${signClass(r.metrics.buyHoldReturnPct)}`}>{pct(r.metrics.buyHoldReturnPct)}</td>
                      <td className="num">{r.metrics.trades}</td>
                      <td className="num">{num(r.metrics.winRatePct, 1)}</td>
                      <td className="num">{r.metrics.profitFactor === null ? (r.metrics.wins ? '∞' : '—') : num(r.metrics.profitFactor)}</td>
                      <td className="num neg">-{num(r.metrics.maxDrawdownPct)}%</td>
                      <td className="num">{num(r.metrics.sharpe)}</td>
                      <td className="num">{num(r.latest.close)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {scan.errors.length > 0 && (
            <details className="assumptions">
              <summary>{scan.skipped} symbol(s) skipped</summary>
              <ul>{scan.errors.map((e) => <li key={e}>{e}</li>)}</ul>
            </details>
          )}
          <p className="muted small">Each run refreshes stale candles first, so results include the latest bar.
            Signals are read at that bar's close; entries and exits would fill at the next open.</p>
        </section>
      )}

      {mode === 'single' && result && m && (
        <>
          <section className="card">
            <div className="card-head">
              <h3>
                {result.symbol} · {result.timeframe} · {m.bars} bars{' '}
                <span className={`sig ${result.latest.signal.toLowerCase()}`}>
                  {signalLabel(result.latest.signal, direction, result.latest.openPnlPct)}
                </span>
              </h3>
              <button className="run-btn secondary" onClick={() => onShowOnChart(result.symbol, result.timeframe as Timeframe, markers())}>
                Show trades on chart
              </button>
            </div>
            <div className="tiles">
              <Tile label="Net profit" value={inr(m.netProfit)} cls={signClass(m.netProfit)} />
              <Tile label="Return" value={pct(m.totalReturnPct)} cls={signClass(m.totalReturnPct)} />
              <Tile label="Buy & hold" value={pct(m.buyHoldReturnPct)} cls={signClass(m.buyHoldReturnPct)} />
              <Tile label="Trades" value={String(m.trades)} />
              <Tile label="Win rate" value={`${num(m.winRatePct, 1)}%`} />
              <Tile label="Profit factor" value={m.profitFactor === null ? '∞' : num(m.profitFactor)} />
              <Tile label="Max drawdown" value={`-${num(m.maxDrawdownPct)}%`} cls="neg" />
              <Tile label="Sharpe" value={num(m.sharpe)} />
              <Tile label="Avg trade" value={pct(m.avgTradePct)} cls={signClass(m.avgTradePct)} />
              <Tile label="Exposure" value={`${num(m.exposurePct, 1)}%`} />
              <Tile label="Avg bars held" value={num(m.avgBarsHeld, 1)} />
              <Tile label="Costs paid" value={inr(m.commissionPaid)} />
            </div>
            <EquityChart points={equity} secondary={drawdown} secondaryKind="drawdown" />
            <p className="muted small">Equity (area) and drawdown % (bars). Signals are evaluated at the bar
              close and filled at the next bar's open; stops/targets are checked against each bar's high/low.</p>
          </section>

          <section className="card">
            <h3>Trades ({result.trades.length})</h3>
            <div className="table-wrap">
              <table className="results-table">
                <thead>
                  <tr><th>Side</th><th>Entry (IST)</th><th className="num">Entry</th><th>Exit (IST)</th>
                    <th className="num">Exit</th><th className="num">Qty</th><th className="num">P&amp;L</th>
                    <th className="num">%</th><th>Reason</th></tr>
                </thead>
                <tbody>
                  {result.trades.map((t, i) => (
                    <tr key={i}>
                      <td>{t.side}</td>
                      <td>{istDateTime(t.entryTime * 1000)}</td>
                      <td className="num">{num(t.entryPrice)}</td>
                      <td>{istDateTime(t.exitTime * 1000)}</td>
                      <td className="num">{num(t.exitPrice)}</td>
                      <td className="num">{t.quantity}</td>
                      <td className={`num ${signClass(t.pnl)}`}>{num(t.pnl)}</td>
                      <td className={`num ${signClass(t.pnlPct)}`}>{pct(t.pnlPct)}</td>
                      <td>{t.exitReason.replace(/_/g, ' ').toLowerCase()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </div>
  )
}

export function Tile({ label, value, cls = '' }: { label: string; value: string; cls?: string }) {
  return (
    <div className="tile">
      <div className="tile-label">{label}</div>
      <div className={`tile-value ${cls}`}>{value}</div>
    </div>
  )
}
