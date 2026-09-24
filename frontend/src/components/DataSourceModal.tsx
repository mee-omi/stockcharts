import { useEffect, useState } from 'react'
import { getDataSource, getHistorySync, runHistorySync, setDataSource } from '../api/client'
import type { DataSourceInfo, HistorySyncInfo } from '../api/types'
import { istDateTime } from '../lib/format'

const LABELS: Record<string, { title: string; desc: string; live: boolean }> = {
  mock: { title: 'Mock (synthetic)', desc: 'Built-in random-walk NSE data. No credentials needed.', live: false },
  zerodha: { title: 'Zerodha (enctoken)', desc: 'History via PyZData with your Kite enctoken; live prices by polling 1-minute bars.', live: true },
  kiteticker: { title: 'Zerodha Kite Connect', desc: 'Official API: history + KiteTicker websocket. Needs ZERODHA_API_KEY and a daily ZERODHA_ACCESS_TOKEN.', live: true },
  groww: { title: 'Groww (mock fallback)', desc: 'No official Groww market-data API — uses synthetic data.', live: false },
  angelone: { title: 'Angel One (SmartAPI)', desc: 'History + SmartWebSocket V2. Needs ANGELONE_API_KEY, CLIENT_ID, PIN and TOTP_SECRET in the environment.', live: true },
}

interface Props {
  onClose: () => void
  onSwitched: (info: DataSourceInfo) => void
}

export default function DataSourceModal({ onClose, onSwitched }: Props) {
  const [info, setInfo] = useState<DataSourceInfo | null>(null)
  const [selected, setSelected] = useState<string>('mock')
  const [enctoken, setEnctoken] = useState('')
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    getDataSource().then((i) => { setInfo(i); setSelected(i.active) }).catch(() => {})
  }, [])

  const needsToken = selected === 'zerodha'
  const [sync, setSync] = useState<HistorySyncInfo | null>(null)
  const loadSync = () => getHistorySync().then(setSync).catch(() => {})
  useEffect(() => { loadSync() }, [])
  // Poll while a sync is running so progress is visible.
  useEffect(() => {
    if (!sync?.status.running) return
    const id = setInterval(loadSync, 3000)
    return () => clearInterval(id)
  }, [sync?.status.running])
  const syncNow = async () => {
    await runHistorySync().catch(() => {})
    setTimeout(loadSync, 500)
  }

  const apply = async () => {
    setBusy(true)
    setStatus(null)
    try {
      const updated = await setDataSource(selected, needsToken ? enctoken : undefined)
      setInfo(updated)
      setStatus({ ok: true, msg: `Switched to ${updated.active}` })
      onSwitched(updated)
    } catch (e: any) {
      setStatus({ ok: false, msg: e?.response?.data?.error ?? 'Failed to switch data source' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>Data source</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        {info && (
          <div className="ds-current">
            Active: <strong>{info.active}</strong>
            {info.status && info.status.feed && (
              <div className="ds-health">
                <span className={`ds-dot ${info.status.connected ? 'live' : 'mock'}`} />
                {info.status.feed}
                {info.status.connected !== undefined && (info.status.connected ? ' · connected' : ' · not connected')}
                {info.status.market_open !== undefined && (info.status.market_open ? ' · market open' : ' · market closed')}
                {info.status.last_tick_ms ? ` · last tick ${istDateTime(info.status.last_tick_ms)}` : ''}
                {info.status.last_error && <div className="err small">{info.status.last_error}</div>}
              </div>
            )}
          </div>
        )}

        <div className="ds-options">
          {(info?.available ?? ['mock', 'zerodha', 'kiteticker']).map((p) => {
            const meta = LABELS[p] ?? { title: p, desc: '', live: false }
            return (
              <label key={p} className={`ds-option ${selected === p ? 'sel' : ''}`}>
                <input type="radio" name="provider" value={p}
                       checked={selected === p} onChange={() => setSelected(p)} />
                <span>
                  <span className="ds-title">
                    {meta.title}
                    {meta.live && <span className="badge pane">live</span>}
                    {info?.active === p && <span className="badge overlay">current</span>}
                  </span>
                  <span className="ds-desc">{meta.desc}</span>
                </span>
              </label>
            )
          })}
        </div>

        {needsToken && (
          <div className="ds-token">
            <label>Zerodha enctoken</label>
            <input type="password" placeholder="paste enctoken from kite.zerodha.com cookies"
                   value={enctoken} onChange={(e) => setEnctoken(e.target.value)} />
            <small className="muted">
              kite.zerodha.com → DevTools → Application → Cookies → copy <code>enctoken</code>.
            </small>
          </div>
        )}

        {sync && (
          <div className="ds-sync">
            <div className="ds-sync-head">
              <strong>History sync</strong>
              <button className="link-btn" onClick={syncNow} disabled={sync.status.running}>
                {sync.status.running ? 'Syncing…' : 'Sync now'}
              </button>
            </div>
            <div className="muted small">
              {sync.trackedSymbols} tracked symbols (watchlists + indices) ·{' '}
              {Object.entries(sync.lookbackDays).map(([tf, d]) => `${tf}: ${d}d`).join(', ')} · daily after the close
            </div>
            <div className="small">
              {sync.status.running
                ? `Running: ${sync.status.done}/${sync.status.symbols} symbols, ${sync.status.candlesStored} candles stored`
                : sync.status.skippedReason
                  ? `Skipped: ${sync.status.skippedReason}`
                  : sync.status.finishedAt
                    ? `Last run ${istDateTime(Date.parse(sync.status.finishedAt))} (${sync.status.trigger}): `
                      + `${sync.status.candlesStored} candles, ${sync.status.failures} failures`
                    : 'Not run yet'}
            </div>
            {sync.status.failureSamples.length > 0 && (
              <div className="err small">Failed: {sync.status.failureSamples.join(', ')}</div>
            )}
          </div>
        )}

        <div className="ds-actions">
          <button className="primary" onClick={apply} disabled={busy || (needsToken && !enctoken.trim())}>
            {busy ? 'Switching…' : 'Apply'}
          </button>
          {status && <span className={`ci-status ${status.ok ? 'ok' : 'err'}`}>{status.msg}</span>}
        </div>
      </div>
    </div>
  )
}
