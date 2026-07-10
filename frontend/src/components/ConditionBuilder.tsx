import { useEffect, useState } from 'react'

/**
 * Guided builder for screener conditions / custom-indicator formulas. Rows of
 * {metric op value|metric} combined with AND/OR generate an editable expression string.
 * The generated expression is the source of truth (also editable directly in advanced mode).
 */

interface Metric {
  value: string
  label: string
  period: boolean
}

const BASE_METRICS: Metric[] = [
  { value: 'close', label: 'Close', period: false },
  { value: 'open', label: 'Open', period: false },
  { value: 'high', label: 'High', period: false },
  { value: 'low', label: 'Low', period: false },
  { value: 'volume', label: 'Volume', period: false },
  { value: 'sma', label: 'SMA(close)', period: true },
  { value: 'ema', label: 'EMA(close)', period: true },
  { value: 'wma', label: 'WMA(close)', period: true },
  { value: 'rsi', label: 'RSI', period: true },
  { value: 'stdev', label: 'StdDev(close)', period: true },
  { value: 'atr', label: 'ATR', period: true },
  { value: 'adx', label: 'ADX', period: true },
  { value: 'cci', label: 'CCI', period: true },
  { value: 'vwap', label: 'VWAP', period: true },
  { value: 'stoch_k', label: 'Stochastic %K', period: true },
  { value: 'macd', label: 'MACD line', period: false },
  { value: 'obv', label: 'OBV', period: false },
]

const SERIES_FN = new Set(['sma', 'ema', 'wma', 'rsi', 'stdev'])
const OHLCV_FN = new Set(['atr', 'adx', 'cci', 'vwap', 'stoch_k'])
const OPERATORS = ['>', '<', '>=', '<=', '==', '!=']

interface Operand {
  metric: string
  period: number
}

interface Row {
  left: Operand
  op: string
  rightKind: 'value' | 'metric'
  value: number
  right: Operand
}

function hasPeriod(metric: string): boolean {
  return SERIES_FN.has(metric) || OHLCV_FN.has(metric)
}

function term(op: Operand): string {
  const m = op.metric
  if (SERIES_FN.has(m)) return `${m}(close, ${op.period})`
  if (OHLCV_FN.has(m)) return `${m}(${op.period})`
  if (m === 'macd') return 'macd(close,12,26)'
  if (m === 'obv') return 'obv()'
  return m // price field or custom indicator name
}

function rowExpr(r: Row): string {
  const right = r.rightKind === 'value' ? String(r.value) : term(r.right)
  return `(${term(r.left)} ${r.op} ${right})`
}

function newRow(): Row {
  return {
    left: { metric: 'rsi', period: 14 },
    op: '<',
    rightKind: 'value',
    value: 30,
    right: { metric: 'sma', period: 50 },
  }
}

interface Props {
  value: string
  onChange: (expr: string) => void
  customNames?: string[]
}

export default function ConditionBuilder({ value, onChange, customNames = [] }: Props) {
  const metrics: Metric[] = [
    ...BASE_METRICS,
    ...customNames.map((n) => ({ value: n, label: `${n} (custom)`, period: false })),
  ]
  const [rows, setRows] = useState<Row[]>([newRow()])
  const [combiner, setCombiner] = useState<'and' | 'or'>('and')
  const [advanced, setAdvanced] = useState(false)

  // Regenerate the expression from rows (unless the user is editing raw text).
  useEffect(() => {
    if (advanced) return
    const expr = rows.map(rowExpr).join(` ${combiner} `)
    onChange(expr)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, combiner, advanced])

  const update = (i: number, patch: Partial<Row>) =>
    setRows((rs) => rs.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))

  const operandPicker = (op: Operand, set: (o: Operand) => void) => (
    <span className="operand">
      <select value={op.metric} onChange={(e) => set({ ...op, metric: e.target.value })}>
        {metrics.map((m) => (
          <option key={m.value} value={m.value}>{m.label}</option>
        ))}
      </select>
      {hasPeriod(op.metric) && (
        <input
          type="number"
          className="period"
          min={1}
          value={op.period}
          onChange={(e) => set({ ...op, period: Number(e.target.value) })}
        />
      )}
    </span>
  )

  return (
    <div className="condition-builder">
      <div className="cb-rows">
        {rows.map((r, i) => (
          <div key={i} className="cb-row">
            {i > 0 && (
              <select className="combiner" value={combiner}
                      onChange={(e) => setCombiner(e.target.value as 'and' | 'or')}>
                <option value="and">AND</option>
                <option value="or">OR</option>
              </select>
            )}
            {operandPicker(r.left, (o) => update(i, { left: o }))}
            <select value={r.op} onChange={(e) => update(i, { op: e.target.value })}>
              {OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
            <select value={r.rightKind}
                    onChange={(e) => update(i, { rightKind: e.target.value as 'value' | 'metric' })}>
              <option value="value">value</option>
              <option value="metric">metric</option>
            </select>
            {r.rightKind === 'value' ? (
              <input type="number" className="value"
                     value={r.value} onChange={(e) => update(i, { value: Number(e.target.value) })} />
            ) : (
              operandPicker(r.right, (o) => update(i, { right: o }))
            )}
            {rows.length > 1 && (
              <button className="cb-del" onClick={() => setRows((rs) => rs.filter((_, idx) => idx !== i))}>
                ✕
              </button>
            )}
          </div>
        ))}
      </div>
      <div className="cb-actions">
        <button className="cb-add" onClick={() => setRows((rs) => [...rs, newRow()])}>+ condition</button>
        <label className="cb-adv">
          <input type="checkbox" checked={advanced} onChange={(e) => setAdvanced(e.target.checked)} />
          advanced
        </label>
      </div>
      <textarea
        className="cb-expr"
        value={value}
        readOnly={!advanced}
        onChange={(e) => onChange(e.target.value)}
        placeholder="generated expression"
        rows={2}
      />
    </div>
  )
}
