import { useEffect, useState } from 'react'
import ConditionBuilder from './ConditionBuilder'
import {
  listCustomIndicators,
  listScreeners,
  listUniverses,
  listWatchlists,
  runScreener,
  saveScreener,
} from '../api/client'
import {
  TIMEFRAMES,
  type ScreenerDef,
  type ScreenerResult,
  type UniverseInfo,
  type UniverseType,
  type Watchlist,
} from '../api/types'

const DEFAULT_COLUMNS = [
  { label: 'RSI14', expression: 'rsi(close,14)' },
  { label: 'SMA50', expression: 'sma(close,50)' },
]

interface Props {
  onPickSymbol: (symbol: string) => void
}

export default function ScreenerView({ onPickSymbol }: Props) {
  const [expression, setExpression] = useState('(rsi(close, 14) < 30)')
  const [universeType, setUniverseType] = useState<UniverseType>('index')
  const [universeValue, setUniverseValue] = useState('NIFTY50')
  const [indices, setIndices] = useState<UniverseInfo[]>([])
  const [timeframe, setTimeframe] = useState('1d')
  const [watchlists, setWatchlists] = useState<Watchlist[]>([])
  const [customNames, setCustomNames] = useState<string[]>([])
  const [saved, setSaved] = useState<ScreenerDef[]>([])
  const [result, setResult] = useState<ScreenerResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saveName, setSaveName] = useState('')

  const refreshSaved = () => listScreeners().then(setSaved).catch(() => {})

  useEffect(() => {
    listWatchlists().then(setWatchlists).catch(() => {})
    listUniverses().then(setIndices).catch(() => {})
    listCustomIndicators().then((cs) => setCustomNames(cs.map((c) => c.name))).catch(() => {})
    refreshSaved()
  }, [])

  const run = async () => {
    setLoading(true)
    setError(null)
    try {
      const value = universeType === 'all' ? undefined : universeValue
      const res = await runScreener({
        expression,
        timeframe,
        universe: { type: universeType, value },
        columns: DEFAULT_COLUMNS,
        limit: 200,
      })
      setResult(res)
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'Screener failed')
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  const doSave = async () => {
    if (!saveName.trim()) return
    const value = universeType === 'all' ? undefined : universeValue
    await saveScreener({ name: saveName.trim(), expression, timeframe, universe: { type: universeType, value } })
    setSaveName('')
    refreshSaved()
  }

  const loadSaved = (def: ScreenerDef) => {
    setExpression(def.expression)
    setTimeframe(def.timeframe)
    setUniverseType(def.universe.type)
    setUniverseValue(def.universe.value ?? (def.universe.type === 'index' ? 'NIFTY50' : ''))
  }

  const colLabels = DEFAULT_COLUMNS.map((c) => c.label)

  return (
    <div className="screener">
      <div className="screener-controls">
        <div className="control-row">
          <label>Universe</label>
          <select
            value={universeType}
            onChange={(e) => {
              const t = e.target.value as UniverseType
              setUniverseType(t)
              setUniverseValue(t === 'index' ? 'NIFTY50' : '')
            }}
          >
            <option value="index">Index</option>
            <option value="all">All stocks</option>
            <option value="list">Custom list</option>
            <option value="watchlist">Watchlist</option>
          </select>
          {universeType === 'index' && (
            <select value={universeValue} onChange={(e) => setUniverseValue(e.target.value)} aria-label="Index">
              {(indices.length ? indices : [{ code: 'NIFTY50', label: 'NIFTY 50', size: 50 }]).map((i) => (
                <option key={i.code} value={i.code}>{i.label} ({i.size})</option>
              ))}
            </select>
          )}
          {universeType === 'list' && (
            <input
              className="universe-input"
              placeholder="RELIANCE, TCS, INFY"
              value={universeValue}
              onChange={(e) => setUniverseValue(e.target.value)}
            />
          )}
          {universeType === 'watchlist' && (
            <select value={universeValue} onChange={(e) => setUniverseValue(e.target.value)}>
              <option value="">— select —</option>
              {watchlists.map((w) => <option key={w.name} value={w.name}>{w.name}</option>)}
            </select>
          )}
          <label>Timeframe</label>
          <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
            {TIMEFRAMES.map((tf) => <option key={tf} value={tf}>{tf}</option>)}
          </select>
        </div>

        <ConditionBuilder value={expression} onChange={setExpression} customNames={customNames} />

        <div className="control-row">
          <button className="run-btn" onClick={run} disabled={loading}>
            {loading ? 'Scanning…' : 'Run screener'}
          </button>
          <input className="save-name" placeholder="save as…" value={saveName}
                 onChange={(e) => setSaveName(e.target.value)} />
          <button onClick={doSave} disabled={!saveName.trim()}>Save</button>
          {saved.length > 0 && (
            <select defaultValue="" onChange={(e) => {
              const def = saved.find((s) => s.name === e.target.value)
              if (def) loadSaved(def)
            }}>
              <option value="">load saved…</option>
              {saved.map((s) => <option key={s.name} value={s.name}>{s.name}</option>)}
            </select>
          )}
        </div>
        {error && <div className="banner error inline">{error}</div>}
      </div>

      <div className="screener-results">
        {result && (
          <div className="results-meta">
            Matched <strong>{result.matched}</strong> of {result.scanned} scanned
            {result.truncated && ' (truncated)'}
          </div>
        )}
        {result && result.matches.length > 0 && (
          <table className="results-table">
            <thead>
              <tr>
                <th>Symbol</th>
                <th>Name</th>
                <th className="num">Close</th>
                {colLabels.map((c) => <th key={c} className="num">{c}</th>)}
              </tr>
            </thead>
            <tbody>
              {result.matches.map((m) => (
                <tr key={m.symbol} onClick={() => onPickSymbol(m.symbol)} className="result-row">
                  <td className="sym">{m.symbol}</td>
                  <td className="name">{m.name}</td>
                  <td className="num">{m.close.toFixed(2)}</td>
                  {colLabels.map((c) => (
                    <td key={c} className="num">
                      {m.columns[c] != null ? m.columns[c]!.toFixed(2) : '—'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {result && result.matches.length === 0 && (
          <div className="no-results">No stocks matched.</div>
        )}
      </div>
    </div>
  )
}
