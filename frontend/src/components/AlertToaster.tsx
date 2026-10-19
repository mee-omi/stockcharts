import { useEffect, useState } from 'react'
import type { AlertEvent, ScanRun } from '../api/types'
import { subscribeAlerts, subscribeScans } from '../ws/stomp'
import { num } from '../lib/format'

const TOAST_MS = 8_000
const MAX_TOASTS = 4

interface Props {
  onOpen: (symbol: string) => void
}

function browserNotify(title: string, body: string, tag: string) {
  if (typeof Notification !== 'undefined' && Notification.permission === 'granted' && document.hidden) {
    try {
      new Notification(title, { body, tag })
    } catch {
      /* some mobile browsers only allow notifications from a service worker */
    }
  }
}

export function scanToastTitle(r: ScanRun): string {
  if (r.status === 'FAILED') return `📊 ${r.scheduleName}: scan failed`
  return `📊 ${r.scheduleName}: ${r.matched} match${r.matched === 1 ? '' : 'es'}${r.newMatches ? ` (${r.newMatches} new)` : ''}`
}

/** Pops a toast (and a browser notification, if permitted) whenever an alert fires or a scheduled scan reports. */
export default function AlertToaster({ onOpen }: Props) {
  const [toasts, setToasts] = useState<AlertEvent[]>([])
  const [scans, setScans] = useState<ScanRun[]>([])

  useEffect(() => subscribeScans((r) => {
    setScans((t) => [r, ...t.filter((x) => x.id !== r.id)].slice(0, MAX_TOASTS))
    setTimeout(() => setScans((t) => t.filter((x) => x.id !== r.id)), TOAST_MS * 2)
    browserNotify(scanToastTitle(r), r.rows.slice(0, 5).map((x) => x.symbol).join(', ') || (r.message ?? ''), `scan-${r.id}`)
  }), [])

  useEffect(() => subscribeAlerts((e) => {
    setToasts((t) => [e, ...t.filter((x) => x.id !== e.id)].slice(0, MAX_TOASTS))
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== e.id)), TOAST_MS)
    browserNotify(`🔔 ${e.symbol} @ ${num(e.price)}`, e.alertName, `alert-${e.id}`)
  }), [])

  if (!toasts.length && !scans.length) return null
  return (
    <div className="toasts" role="status" aria-live="polite">
      {scans.map((r) => (
        <div key={`scan-${r.id}`} className="toast scan-toast"
             onClick={() => r.rows[0] && onOpen(r.rows[0].symbol)}>
          <strong>{scanToastTitle(r)}</strong>
          <div className="muted small">
            {r.status === 'FAILED' ? r.message : r.rows.slice(0, 5).map((x) => `${x.symbol} ${x.signal.toLowerCase()}`).join(' · ')}
          </div>
          <button className="toast-close" aria-label="Dismiss"
                  onClick={(ev) => { ev.stopPropagation(); setScans((x) => x.filter((y) => y.id !== r.id)) }}>✕</button>
        </div>
      ))}
      {toasts.map((t) => (
        <div key={t.id} className="toast" onClick={() => onOpen(t.symbol)}>
          <strong>🔔 {t.symbol}</strong> @ {num(t.price)}
          <div className="muted small">{t.alertName}</div>
          <button className="toast-close" aria-label="Dismiss"
                  onClick={(ev) => { ev.stopPropagation(); setToasts((x) => x.filter((y) => y.id !== t.id)) }}>✕</button>
        </div>
      ))}
    </div>
  )
}
