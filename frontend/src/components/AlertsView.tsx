import { useCallback, useEffect, useState } from 'react'
import {
  apiError,
  createAlert,
  deleteAlert,
  listAlertEvents,
  listAlerts,
  setAlertStatus,
} from '../api/client'
import { TIMEFRAMES, type Alert, type AlertEvent, type AlertTriggerMode } from '../api/types'
import { subscribeAlerts } from '../ws/stomp'
import { istDateTime, num } from '../lib/format'
import StockSearch from './StockSearch'

type Template = 'cross_above' | 'cross_below' | 'above' | 'below' | 'custom'

const TEMPLATES: { value: Template; label: string }[] = [
  { value: 'cross_above', label: 'Price crosses above' },
  { value: 'cross_below', label: 'Price crosses below' },
  { value: 'above', label: 'Price is above' },
  { value: 'below', label: 'Price is below' },
  { value: 'custom', label: 'Custom condition' },
]

/** Price templates compile to the same expression language as screeners. */
export function templateExpression(t: Template, level: string, custom: string): string {
  const v = Number(level)
  if (t !== 'custom' && (level.trim() === '' || !Number.isFinite(v))) return ''
  switch (t) {
    case 'cross_above': return `crossover(close, ${v})`
    case 'cross_below': return `crossunder(close, ${v})`
    case 'above': return `close > ${v}`
    case 'below': return `close < ${v}`
    default: return custom.trim()
  }
}

interface Props {
  defaultSymbol?: string
  onPickSymbol: (symbol: string) => void
}

export default function AlertsView({ defaultSymbol, onPickSymbol }: Props) {
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [events, setEvents] = useState<AlertEvent[]>([])
  const [symbol, setSymbol] = useState(defaultSymbol ?? '')
  const [timeframe, setTimeframe] = useState('5m')
  const [template, setTemplate] = useState<Template>('cross_above')
  const [level, setLevel] = useState('')
  const [custom, setCustom] = useState('rsi(close, 14) < 30')
  const [mode, setMode] = useState<AlertTriggerMode>('ONCE')
  const [name, setName] = useState('')
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [perm, setPerm] = useState<NotificationPermission | 'unsupported'>(
    typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
  )

  const reload = useCallback(() => {
    listAlerts().then(setAlerts).catch(() => setStatus({ ok: false, msg: 'Could not load alerts' }))
    listAlertEvents(50).then(setEvents).catch(() => {})
  }, [])

  useEffect(() => { reload() }, [reload])
  useEffect(() => subscribeAlerts(() => reload()), [reload])
  useEffect(() => { if (defaultSymbol) setSymbol(defaultSymbol) }, [defaultSymbol])

  const needsLevel = template !== 'custom'
  const expression = templateExpression(template, level, custom)

  const submit = async () => {
    if (!symbol) return setStatus({ ok: false, msg: 'Pick a symbol' })
    if (needsLevel && !(Number(level) > 0)) return setStatus({ ok: false, msg: 'Enter a price level' })
    setBusy(true)
    try {
      const a = await createAlert({ name: name.trim() || undefined, symbol, timeframe, expression, triggerMode: mode })
      setStatus({ ok: true, msg: `Alert "${a.name}" is active` })
      setLevel('')
      setName('')
      reload()
    } catch (e) {
      setStatus({ ok: false, msg: apiError(e, 'Could not create alert') })
    } finally {
      setBusy(false)
    }
  }

  const toggle = async (a: Alert) => {
    try {
      await setAlertStatus(a.id, a.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE')
      reload()
    } catch (e) {
      setStatus({ ok: false, msg: apiError(e, 'Update failed') })
    }
  }

  const remove = async (a: Alert) => {
    await deleteAlert(a.id).catch(() => {})
    reload()
  }

  const askPermission = async () => {
    if (typeof Notification === 'undefined') return
    setPerm(await Notification.requestPermission())
  }

  return (
    <div className="page">
      <section className="card">
        <h3>New alert</h3>
        <div className="form-grid">
          <label>Symbol
            {symbol ? (
              <span className="compare-chip">
                <strong>{symbol}</strong>
                <button onClick={() => setSymbol('')} aria-label="Change symbol">✕</button>
              </span>
            ) : (
              <StockSearch compact placeholder="Symbol…" ariaLabel="Alert symbol" onSelect={setSymbol} />
            )}
          </label>
          <label>Timeframe
            <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
              {TIMEFRAMES.map((tf) => <option key={tf} value={tf}>{tf}</option>)}
            </select>
          </label>
          <label>Condition
            <select value={template} onChange={(e) => setTemplate(e.target.value as Template)} aria-label="Condition">
              {TEMPLATES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </label>
          {needsLevel ? (
            <label>Price
              <input type="number" inputMode="decimal" value={level} onChange={(e) => setLevel(e.target.value)}
                     placeholder="e.g. 3000" aria-label="Price level" />
            </label>
          ) : (
            <label className="span-2">Expression
              <textarea className="cb-expr" rows={2} value={custom} onChange={(e) => setCustom(e.target.value)}
                        aria-label="Alert expression" />
            </label>
          )}
          <label>Fires
            <select value={mode} onChange={(e) => setMode(e.target.value as AlertTriggerMode)}>
              <option value="ONCE">Once</option>
              <option value="EVERY_BAR">Once per bar while true</option>
            </select>
          </label>
          <label>Name (optional)
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="auto" />
          </label>
        </div>
        <div className="form-actions">
          <code className="expr-preview">{expression || '—'}</code>
          <button className="run-btn" onClick={submit} disabled={busy}>{busy ? 'Saving…' : 'Create alert'}</button>
          {status && <span className={status.ok ? 'ok' : 'err'}>{status.msg}</span>}
        </div>
        <p className="muted small">
          Alerts are checked every 15 seconds against the latest bar. Triggers appear here, as an in-app
          toast, as a browser notification{perm === 'granted' ? ' (enabled)' : ''} and — if configured on the
          server — on Telegram or a webhook.
          {perm === 'default' && <> <button className="link-btn" onClick={askPermission}>Enable browser notifications</button></>}
          {perm === 'denied' && ' Browser notifications are blocked for this site.'}
        </p>
      </section>

      <section className="card">
        <h3>Alerts ({alerts.length})</h3>
        {!alerts.length ? <div className="muted">No alerts yet.</div> : (
          <div className="table-wrap">
            <table className="results-table">
              <thead>
                <tr><th>Name</th><th>Symbol</th><th>TF</th><th>Condition</th><th>Status</th><th className="num">Fired</th><th /></tr>
              </thead>
              <tbody>
                {alerts.map((a) => (
                  <tr key={a.id}>
                    <td>{a.name}</td>
                    <td className="sym"><button className="link-btn" onClick={() => onPickSymbol(a.symbol)}>{a.symbol}</button></td>
                    <td>{a.timeframe}</td>
                    <td><code>{a.expression}</code></td>
                    <td><span className={`status-pill ${a.status.toLowerCase()}`}>{a.status}</span></td>
                    <td className="num">{a.triggerCount}{a.lastTriggeredAt ? ` · ${istDateTime(a.lastTriggeredAt)}` : ''}</td>
                    <td className="row-actions">
                      <button onClick={() => toggle(a)}>{a.status === 'ACTIVE' ? 'Pause' : 'Re-arm'}</button>
                      <button onClick={() => remove(a)} aria-label={`Delete alert ${a.name}`}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h3>Recent triggers</h3>
        {!events.length ? <div className="muted">Nothing has fired yet.</div> : (
          <div className="table-wrap">
            <table className="results-table">
              <thead><tr><th>When (IST)</th><th>Alert</th><th>Symbol</th><th className="num">Price</th></tr></thead>
              <tbody>
                {events.map((e) => (
                  <tr key={e.id}>
                    <td>{istDateTime(e.triggeredAt)}</td>
                    <td>{e.alertName}</td>
                    <td className="sym">{e.symbol}</td>
                    <td className="num">{num(e.price)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
