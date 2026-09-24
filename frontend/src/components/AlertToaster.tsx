import { useEffect, useState } from 'react'
import type { AlertEvent } from '../api/types'
import { subscribeAlerts } from '../ws/stomp'
import { num } from '../lib/format'

const TOAST_MS = 8_000
const MAX_TOASTS = 4

interface Props {
  onOpen: (symbol: string) => void
}

/** Pops a toast (and a browser notification, if permitted) whenever any alert fires. */
export default function AlertToaster({ onOpen }: Props) {
  const [toasts, setToasts] = useState<AlertEvent[]>([])

  useEffect(() => subscribeAlerts((e) => {
    setToasts((t) => [e, ...t.filter((x) => x.id !== e.id)].slice(0, MAX_TOASTS))
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== e.id)), TOAST_MS)
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted' && document.hidden) {
      try {
        new Notification(`🔔 ${e.symbol} @ ${num(e.price)}`, { body: e.alertName, tag: `alert-${e.id}` })
      } catch {
        /* some mobile browsers only allow notifications from a service worker */
      }
    }
  }), [])

  if (!toasts.length) return null
  return (
    <div className="toasts" role="status" aria-live="polite">
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
