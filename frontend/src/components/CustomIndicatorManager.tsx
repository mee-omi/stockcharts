import { useEffect, useState } from 'react'
import {
  createCustomIndicator,
  deleteCustomIndicator,
  listCustomIndicators,
  validateExpression,
} from '../api/client'
import type { CustomIndicator } from '../api/types'

const SNIPPETS = ['sma(close, 20)', 'ema(close, 9)', 'rsi(close, 14)', 'atr(14)',
  'macd(close, 12, 26)', 'vwap(20)', 'close', 'high', 'low']

interface Props {
  onChanged: () => void
  onClose: () => void
}

export default function CustomIndicatorManager({ onChanged, onClose }: Props) {
  const [items, setItems] = useState<CustomIndicator[]>([])
  const [name, setName] = useState('')
  const [expression, setExpression] = useState('ema(close, 9) - ema(close, 21)')
  const [overlay, setOverlay] = useState(false)
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)

  const reload = () => listCustomIndicators().then(setItems).catch(() => {})
  useEffect(() => { reload() }, [])

  const validate = async () => {
    try {
      const v = await validateExpression(expression)
      setStatus({ ok: v.valid, msg: v.valid ? 'Valid ✓' : v.error ?? 'Invalid' })
    } catch {
      setStatus({ ok: false, msg: 'Validation failed' })
    }
  }

  const create = async () => {
    try {
      await createCustomIndicator({ name, expression, overlay })
      setName('')
      setStatus({ ok: true, msg: 'Created ✓' })
      reload()
      onChanged()
    } catch (e: any) {
      setStatus({ ok: false, msg: e?.response?.data?.error ?? 'Create failed' })
    }
  }

  const remove = async (n: string) => {
    await deleteCustomIndicator(n)
    reload()
    onChanged()
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>Custom indicators</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        <div className="ci-form">
          <input className="ci-name" placeholder="name (e.g. ema_spread)"
                 value={name} onChange={(e) => setName(e.target.value)} />
          <textarea className="ci-expr" rows={3} value={expression}
                    onChange={(e) => setExpression(e.target.value)}
                    placeholder="expression, e.g. ema(close,9) - ema(close,21)" />
          <div className="ci-snippets">
            {SNIPPETS.map((s) => (
              <button key={s} className="snippet"
                      onClick={() => setExpression((x) => (x ? `${x} ` : '') + s)}>{s}</button>
            ))}
          </div>
          <div className="ci-actions">
            <label><input type="checkbox" checked={overlay}
                          onChange={(e) => setOverlay(e.target.checked)} /> overlay on price</label>
            <button onClick={validate}>Validate</button>
            <button className="primary" onClick={create} disabled={!name.trim()}>Create</button>
          </div>
          {status && <div className={`ci-status ${status.ok ? 'ok' : 'err'}`}>{status.msg}</div>}
        </div>

        <div className="ci-list">
          {items.length === 0 && <div className="muted">No custom indicators yet.</div>}
          {items.map((c) => (
            <div key={c.name} className="ci-item">
              <div>
                <strong>{c.name}</strong>
                <code>{c.expression}</code>
              </div>
              <button className="cb-del" onClick={() => remove(c.name)}>✕</button>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
