import { useEffect, useState } from 'react'
import { getTradingBroker, setTradingBroker } from '../api/client'
import type { TradingBrokerInfo } from '../api/types'

const BROKER_META: Record<string, { title: string; desc: string; live: boolean }> = {
  mock: {
    title: 'Paper Trading (mock)',
    desc: 'Simulated trades with ₹10 lakh virtual cash. No credentials needed.',
    live: false,
  },
  zerodha: {
    title: 'Zerodha (Kite Connect)',
    desc: 'Real NSE/BSE orders via Kite Connect API. Requires api_key + daily access_token.',
    live: true,
  },
  angelone: {
    title: 'Angel One (SmartAPI)',
    desc: 'Real trades via SmartAPI. Requires api_key, client_id, and TOTP secret.',
    live: true,
  },
  groww: {
    title: 'Groww (stub)',
    desc: 'No official Groww trading API yet. Selecting this will show an error.',
    live: false,
  },
}

interface Props {
  onClose: () => void
  onSwitched: (info: TradingBrokerInfo) => void
}

export default function BrokerConfigModal({ onClose, onSwitched }: Props) {
  const [info, setInfo] = useState<TradingBrokerInfo | null>(null)
  const [selected, setSelected] = useState('mock')
  // Zerodha fields
  const [apiKey, setApiKey] = useState('')
  const [accessToken, setAccessToken] = useState('')
  // Angel One fields
  const [clientId, setClientId] = useState('')
  const [totpSecret, setTotpSecret] = useState('')
  const [pin, setPin] = useState('')
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    getTradingBroker()
      .then((i) => { setInfo(i); setSelected(i.active) })
      .catch(() => {})
  }, [])

  const apply = async () => {
    setBusy(true)
    setStatus(null)
    try {
      const req: Record<string, string> = { broker: selected }
      if (selected === 'zerodha') {
        req.apiKey = apiKey
        req.accessToken = accessToken
      } else if (selected === 'angelone') {
        req.apiKey = apiKey
        req.clientId = clientId
        req.totpSecret = totpSecret
        req.pin = pin
      }
      const updated = await setTradingBroker(req as any)
      setInfo(updated)
      setStatus({ ok: true, msg: `Switched to ${updated.active}` })
      onSwitched(updated)
    } catch (e: any) {
      setStatus({ ok: false, msg: e?.response?.data?.error ?? e?.message ?? 'Failed to switch broker' })
    } finally {
      setBusy(false)
    }
  }

  const canApply = () => {
    if (selected === 'zerodha') return apiKey.trim() && accessToken.trim()
    if (selected === 'angelone') return apiKey.trim() && clientId.trim() && pin.trim() && totpSecret.trim()
    return true
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>Trading broker</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        {info && (
          <div className="ds-current">
            Active: <strong>{BROKER_META[info.active]?.title ?? info.active}</strong>
          </div>
        )}

        <div className="ds-options">
          {(info?.available ?? Object.keys(BROKER_META)).map((b) => {
            const meta = BROKER_META[b] ?? { title: b, desc: '', live: false }
            return (
              <label key={b} className={`ds-option ${selected === b ? 'sel' : ''}`}>
                <input
                  type="radio"
                  name="broker"
                  value={b}
                  checked={selected === b}
                  onChange={() => setSelected(b)}
                />
                <span>
                  <span className="ds-title">
                    {meta.title}
                    {meta.live && <span className="badge pane">live</span>}
                    {info?.active === b && <span className="badge overlay">current</span>}
                  </span>
                  <span className="ds-desc">{meta.desc}</span>
                </span>
              </label>
            )
          })}
        </div>

        {selected === 'zerodha' && (
          <div className="broker-creds">
            <div className="cred-row">
              <label>API Key</label>
              <input
                type="text"
                placeholder="Kite Connect api_key"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
              />
            </div>
            <div className="cred-row">
              <label>Access Token</label>
              <input
                type="password"
                placeholder="Daily access_token from Kite OAuth"
                value={accessToken}
                onChange={(e) => setAccessToken(e.target.value)}
              />
              <small className="muted">
                Regenerate every day via Kite Connect OAuth flow.
              </small>
            </div>
          </div>
        )}

        {selected === 'angelone' && (
          <div className="broker-creds">
            <div className="cred-row">
              <label>API Key</label>
              <input
                type="text"
                placeholder="SmartAPI api_key"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
              />
            </div>
            <div className="cred-row">
              <label>Client ID</label>
              <input
                type="text"
                placeholder="Angel One login ID"
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
              />
            </div>
            <div className="cred-row">
              <label>MPIN</label>
              <input
                type="password"
                inputMode="numeric"
                placeholder="Angel One account MPIN"
                value={pin}
                onChange={(e) => setPin(e.target.value)}
              />
            </div>
            <div className="cred-row">
              <label>TOTP Secret</label>
              <input
                type="password"
                placeholder="TOTP secret from Angel One dashboard"
                value={totpSecret}
                onChange={(e) => setTotpSecret(e.target.value)}
              />
              <small className="muted">
                Enable TOTP at myaccount.angelone.in → My Profile → Enable TOTP.
              </small>
            </div>
          </div>
        )}

        <div className="ds-actions">
          <button
            className="primary"
            onClick={apply}
            disabled={busy || !canApply()}
          >
            {busy ? 'Switching…' : 'Apply'}
          </button>
          {status && (
            <span className={`ci-status ${status.ok ? 'ok' : 'err'}`}>{status.msg}</span>
          )}
        </div>
      </div>
    </div>
  )
}
