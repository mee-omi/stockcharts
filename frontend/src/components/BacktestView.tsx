import { useMemo, useState } from 'react'
import { apiError, runBacktest } from '../api/client'
import {
  TIMEFRAMES,
  type BacktestDirection,
  type BacktestResult,
  type Timeframe,
  type TradeMarker,
} from '../api/types'
import { inr, istDateTime, num, pct, signClass } from '../lib/format'
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

export default function BacktestView({ defaultSymbol, onShowOnChart }: Props) {
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

  const applyPreset = (i: number) => {
    const p = PRESETS[i]
    if (!p) return
    setEntry(p.entry)
    setExit(p.exit)
    setDirection(p.direction)
  }

  const run = async () => {
    if (!symbol) return setError('Pick a symbol')
    setBusy(true)
    setError(null)
    try {
      setResult(await runBacktest({
        symbol, timeframe, direction, entry, exit: exit.trim() || undefined,
        stopLossPct: optionalNumber(stopLoss), takeProfitPct: optionalNumber(takeProfit),
        initialCapital: optionalNumber(capital), positionSizePct: optionalNumber(size),
        commissionPct: optionalNumber(commission), slippagePct: optionalNumber(slippage),
        from: from ? new Date(from).getTime() : undefined,
        to: to ? new Date(`${to}T23:59:59`).getTime() : undefined,
      }))
    } catch (e) {
      setError(apiError(e, 'Backtest failed'))
      setResult(null)
    } finally {
      setBusy(false)
    }
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
      <section className="card">
        <h3>Strategy</h3>
        <div className="form-grid">
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
        <div className="form-actions">
          <button className="run-btn" onClick={run} disabled={busy}>{busy ? 'Running…' : 'Run backtest'}</button>
          {error && <span className="err">{error}</span>}
          {!from && <span className="muted small">Default range: 3y (1d), 180d (1h), 10d (1m), 30d otherwise.</span>}
        </div>
      </section>

      {result && m && (
        <>
          <section className="card">
            <div className="card-head">
              <h3>{result.symbol} · {result.timeframe} · {m.bars} bars</h3>
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
