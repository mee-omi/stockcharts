import { useEffect, useState } from 'react'
import { getDataSource, setDataSource } from '../api/client'
import type { DataSourceInfo } from '../api/types'

const LABELS: Record<string, { title: string; desc: string; live: boolean }> = {
  mock: { title: 'Mock (synthetic)', desc: 'Built-in random-walk NSE data. No credentials needed.', live: false },
  zerodha: { title: 'Zerodha (live)', desc: 'Real NSE data via PyZData using your Kite enctoken.', live: true },
  kiteticker: { title: 'Zerodha KiteTicker', desc: 'Official websocket tick stream (paid Kite Connect).', live: true },
  groww: { title: 'Groww (mock fallback)', desc: 'No official Groww market-data API — uses synthetic data.', live: false },
  angelone: { title: 'Angel One (SmartAPI)', desc: 'Real NSE data via SmartAPI. Requires credentials set in environment.', live: true },
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

  const needsToken = selected === 'zerodha' || selected === 'kiteticker'

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
