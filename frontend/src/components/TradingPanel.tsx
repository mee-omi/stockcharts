import { useCallback, useEffect, useState } from 'react'
import AsyncSelect from 'react-select/async'
import {
  apiErrorMessage,
  cancelOrder,
  getFunds,
  getHoldings,
  getOrders,
  getPositions,
  getPaperAccount,
  getRiskState,
  getTradingBroker,
  placeOrder,
  previewOrder,
  resetPaperAccount,
  setKillSwitch,
  updateRiskSettings,
} from '../api/client'
import { searchInstruments } from '../api/client'
import { subscribePaper } from '../ws/stomp'
import type {
  Funds,
  Holding,
  Order,
  OrderPreview,
  OrderType,
  PaperAccount,
  PlaceOrderRequest,
  Position,
  Positions,
  ProductType,
  RiskSettings,
  RiskState,
  TradeMarker,
  TransactionType,
} from '../api/types'

type TradingTab = 'order' | 'orders' | 'positions' | 'holdings' | 'funds' | 'risk'

interface SymbolOption {
  value: string
  label: string
}

const fmt = (n: number) =>
  new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n)

const pnlClass = (n: number) => (n >= 0 ? 'pnl-pos' : 'pnl-neg')

// ---------------------------------------------------------------------------
// Order form — used in both compact (chart-side) and full modes
// ---------------------------------------------------------------------------

interface OrderFormProps {
  onOrderPlaced: (marker: TradeMarker) => void
  /** Pre-fill symbol from the active chart */
  defaultSymbol?: string
  /** Replay mode: orders are stamped with the current bar's time */
  replayMode?: boolean
  replayBarTime?: number
  replayBarPrice?: number
  compact?: boolean
}

function OrderForm({
  onOrderPlaced,
  defaultSymbol,
  replayMode,
  replayBarTime,
  replayBarPrice,
  compact,
}: OrderFormProps) {
  const [selectedOption, setSelectedOption] = useState<SymbolOption | null>(
    defaultSymbol ? { value: defaultSymbol, label: defaultSymbol } : null,
  )
  const [symbol, setSymbol] = useState(defaultSymbol ?? '')
  const [exchange, setExchange] = useState('NSE')
  const [txnType, setTxnType] = useState<TransactionType>('BUY')
  const [orderType, setOrderType] = useState<OrderType>('MARKET')
  const [product, setProduct] = useState<ProductType>('MIS')
  const [qty, setQty] = useState('')
  const [price, setPrice] = useState('')
  const [triggerPrice, setTriggerPrice] = useState('')
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)
  // A live-broker order awaiting the user's explicit confirmation.
  const [pending, setPending] = useState<{ req: PlaceOrderRequest; preview: OrderPreview } | null>(null)

  // Sync symbol when the chart changes the active stock.
  useEffect(() => {
    if (!defaultSymbol) return
    setSymbol(defaultSymbol)
    setSelectedOption({ value: defaultSymbol, label: defaultSymbol })
  }, [defaultSymbol])

  // Pre-fill price with current bar's close in replay mode.
  useEffect(() => {
    if (replayMode && replayBarPrice) {
      setPrice(String(replayBarPrice))
    }
  }, [replayMode, replayBarPrice])

  const needsPrice = orderType === 'LIMIT' || orderType === 'SL'
  const needsTrigger = orderType === 'SL' || orderType === 'SL-M'

  const submit = async () => {
    if (!symbol || !qty || parseInt(qty) < 1) {
      setStatus({ ok: false, msg: 'Symbol and quantity are required.' })
      return
    }
    setBusy(true)
    setStatus(null)
    const req: PlaceOrderRequest = {
      symbol,
      exchange,
      transaction_type: txnType,
      order_type: orderType,
      product,
      quantity: parseInt(qty),
      price: needsPrice && price ? parseFloat(price) : undefined,
      trigger_price: needsTrigger && triggerPrice ? parseFloat(triggerPrice) : undefined,
      tag: replayMode ? 'replay' : undefined,
    }
    try {
      const preview = await previewOrder(req)
      const a = preview.assessment
      if (a.violations.length) {
        setStatus({ ok: false, msg: `Blocked: ${a.violations.join(' · ')}` })
      } else if (preview.confirmationRequired) {
        setPending({ req, preview })
      } else {
        await execute(req, preview.confirmationToken)
      }
    } catch (e: any) {
      setStatus({ ok: false, msg: apiErrorMessage(e, 'Order failed') })
    } finally {
      setBusy(false)
    }
  }

  const execute = async (req: PlaceOrderRequest, token: string | null) => {
    setBusy(true)
    try {
      const order = await placeOrder(req, token)
      const detail = order.message ? ` · ${order.message}` : ''
      setStatus({ ok: order.status !== 'REJECTED', msg: `${order.status} — ${order.order_id}${detail}` })
      if (order.status === 'REJECTED') return
      // Build the chart marker — use replay bar time if in replay mode.
      const markerTime = replayMode && replayBarTime
        ? replayBarTime
        : Math.floor(Date.now() / 1000)
      const markerPrice = order.average_price || (replayMode && replayBarPrice ? replayBarPrice : parseFloat(price) || 0)
      onOrderPlaced({
        time: markerTime,
        side: req.transaction_type,
        price: markerPrice,
        quantity: req.quantity,
        orderId: order.order_id,
      })
      setQty('')
      if (!replayMode) setPrice('')
      setTriggerPrice('')
    } catch (e: any) {
      setStatus({ ok: false, msg: apiErrorMessage(e, 'Order failed') })
    } finally {
      setBusy(false)
      setPending(null)
    }
  }

  const loadOptions = async (input: string): Promise<SymbolOption[]> => {
    const list = await searchInstruments(input)
    return list.slice(0, 50).map((i) => ({ value: i.symbol, label: `${i.symbol} — ${i.name}` }))
  }

  return (
    <div className={`order-form${compact ? ' order-form-compact' : ''}`}>
      {replayMode && replayBarPrice && (
        <div className="replay-price-badge">
          ⏵ Replay — bar close: <strong>₹{fmt(replayBarPrice)}</strong>
        </div>
      )}

      <div className="of-row">
        <label>Symbol</label>
        <AsyncSelect<SymbolOption>
          cacheOptions
          defaultOptions
          loadOptions={loadOptions}
          placeholder="Search symbol…"
          value={selectedOption}
          onChange={(opt) => {
            if (opt) { setSelectedOption(opt); setSymbol(opt.value) }
          }}
          classNamePrefix="stocksel"
          styles={{
            control: (b) => ({ ...b, background: '#161b22', borderColor: '#30363d', minHeight: 36 }),
            menu: (b) => ({ ...b, background: '#161b22', zIndex: 50 }),
            input: (b) => ({ ...b, color: '#e6edf3' }),
            singleValue: (b) => ({ ...b, color: '#e6edf3' }),
            placeholder: (b) => ({ ...b, color: '#8b949e' }),
            option: (b, s) => ({ ...b, background: s.isFocused ? '#21262d' : 'transparent', color: '#e6edf3', cursor: 'pointer' }),
          }}
        />
      </div>

      <div className="of-row">
        <label>Action</label>
        <div className="of-seg">
          <button className={`seg-btn buy ${txnType === 'BUY' ? 'active' : ''}`}
                  onClick={() => setTxnType('BUY')}>BUY</button>
          <button className={`seg-btn sell ${txnType === 'SELL' ? 'active' : ''}`}
                  onClick={() => setTxnType('SELL')}>SELL</button>
        </div>
      </div>

      {!compact && (
        <div className="of-row">
          <label>Exchange</label>
          <div className="of-seg">
            {(['NSE', 'BSE'] as const).map((ex) => (
              <button key={ex} className={`seg-btn ${exchange === ex ? 'active' : ''}`}
                      onClick={() => setExchange(ex)}>{ex}</button>
            ))}
          </div>
        </div>
      )}

      <div className="of-row">
        <label>Product</label>
        <div className="of-seg">
          {(['MIS', 'CNC', 'NRML'] as const).map((p) => (
            <button key={p} className={`seg-btn ${product === p ? 'active' : ''}`}
                    onClick={() => setProduct(p)}>{p}</button>
          ))}
        </div>
      </div>

      <div className="of-row">
        <label>Order type</label>
        <select className="of-select" value={orderType}
                onChange={(e) => setOrderType(e.target.value as OrderType)}>
          <option value="MARKET">MARKET</option>
          <option value="LIMIT">LIMIT</option>
          <option value="SL">SL (Stop-Loss Limit)</option>
          <option value="SL-M">SL-M (Stop-Loss Market)</option>
        </select>
      </div>

      <div className="of-row">
        <label>Quantity</label>
        <input className="of-input" type="number" min="1" placeholder="e.g. 10"
               value={qty} onChange={(e) => setQty(e.target.value)} />
      </div>

      {(needsPrice || (replayMode && orderType === 'MARKET')) && (
        <div className="of-row">
          <label>{needsPrice ? 'Price (₹)' : 'Fill price (₹)'}</label>
          <input className="of-input" type="number" min="0" step="0.05"
                 placeholder={replayMode ? 'Bar close price' : 'Limit price'}
                 value={price} onChange={(e) => setPrice(e.target.value)} />
        </div>
      )}

      {needsTrigger && (
        <div className="of-row">
          <label>Trigger (₹)</label>
          <input className="of-input" type="number" min="0" step="0.05" placeholder="Trigger price"
                 value={triggerPrice} onChange={(e) => setTriggerPrice(e.target.value)} />
        </div>
      )}

      {pending && (
        <OrderConfirm
          req={pending.req}
          preview={pending.preview}
          busy={busy}
          onConfirm={() => execute(pending.req, pending.preview.confirmationToken)}
          onCancel={() => { setPending(null); setStatus({ ok: false, msg: 'Order cancelled' }) }}
        />
      )}

      <div className="of-actions">
        <button
          className={`of-submit ${txnType === 'BUY' ? 'buy' : 'sell'}`}
          onClick={submit}
          disabled={busy || pending !== null}
        >
          {busy ? 'Placing…' : `${txnType} ${symbol || '—'}`}
        </button>
        {status && (
          <span className={`ci-status ${status.ok ? 'ok' : 'err'}`}>{status.msg}</span>
        )}
      </div>
    </div>
  )
}

/** Final check before a real-money order: exactly what will be sent, its value, and any warnings. */
function OrderConfirm({ req, preview, busy, onConfirm, onCancel }: {
  req: PlaceOrderRequest
  preview: OrderPreview
  busy: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const a = preview.assessment
  const [secondsLeft, setSecondsLeft] = useState(60)
  useEffect(() => {
    const expires = preview.expiresAt ? new Date(preview.expiresAt).getTime() : Date.now() + 60_000
    const id = window.setInterval(() => {
      const left = Math.max(0, Math.round((expires - Date.now()) / 1000))
      setSecondsLeft(left)
      if (left === 0) onCancel()
    }, 500)
    return () => window.clearInterval(id)
  }, [preview.expiresAt, onCancel])

  const priceText = req.order_type === 'MARKET' ? `MARKET (≈ ₹${a.price != null ? fmt(a.price) : '?'})`
    : `${req.order_type} ₹${fmt(req.price ?? req.trigger_price ?? 0)}`
  return (
    <div className="order-confirm" role="dialog" aria-label="Confirm order">
      <div className="order-confirm-title">
        Confirm LIVE order on <strong>{a.broker}</strong>
      </div>
      <div className={`order-confirm-line ${req.transaction_type === 'BUY' ? 'pnl-pos' : 'pnl-neg'}`}>
        {req.transaction_type} {req.quantity} × {req.symbol} ({req.exchange}, {req.product})
      </div>
      <div className="order-confirm-line">{priceText}</div>
      <div className="order-confirm-line">
        Est. value: <strong>{a.estimatedValue != null ? `₹${fmt(a.estimatedValue)}` : 'unknown'}</strong>
        {' · '}orders today: {a.ordersToday}
        {a.dayPnl != null && <> · day P&L: <span className={pnlClass(a.dayPnl)}>₹{fmt(a.dayPnl)}</span></>}
      </div>
      {a.warnings.map((w) => <div key={w} className="order-confirm-warn">⚠ {w}</div>)}
      <div className="order-confirm-actions">
        <button className="panel-btn" onClick={onCancel} disabled={busy}>Cancel</button>
        <button className={`of-submit ${req.transaction_type === 'BUY' ? 'buy' : 'sell'}`}
                onClick={onConfirm} disabled={busy}>
          {busy ? 'Placing…' : `Confirm ${req.transaction_type} (${secondsLeft}s)`}
        </button>
      </div>
    </div>
  )
}

function MiniOrderList({ refresh }: { refresh: number }) {
  const [orders, setOrders] = useState<Order[]>([])

  const load = useCallback(() => {
    getOrders().then((all) => setOrders(all.slice(0, 6))).catch(() => {})
  }, [])
  useEffect(() => { load() }, [load, refresh])

  if (!orders.length) return <div className="trade-empty" style={{ fontSize: 12 }}>No orders yet.</div>

  const statusClass = (s: string) => {
    if (s === 'COMPLETE') return 'os-complete'
    if (s === 'CANCELLED' || s === 'REJECTED') return 'os-cancelled'
    return 'os-open'
  }

  return (
    <div className="mini-orders">
      {orders.map((o) => (
        <div key={o.order_id} className="mini-order-row">
          <span className={`txn-badge ${o.transaction_type === 'BUY' ? 'buy' : 'sell'}`}>
            {o.transaction_type[0]}
          </span>
          <span className="mini-order-sym">{o.symbol}</span>
          <span className="mini-order-qty">{o.quantity}</span>
          <span className="mini-order-price">₹{fmt(o.average_price || o.price)}</span>
          <span className={`order-status ${statusClass(o.status)}`}>{o.status}</span>
        </div>
      ))}
    </div>
  )
}

function OrderBook({ refresh }: { refresh: number }) {
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    getOrders()
      .then(setOrders)
      .catch(() => setErr('Failed to load orders'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load, refresh])

  const cancel = async (id: string) => {
    try { await cancelOrder(id); load() } catch { setErr('Cancel failed') }
  }

  const statusClass = (s: string) => {
    if (s === 'COMPLETE') return 'os-complete'
    if (s === 'CANCELLED' || s === 'REJECTED') return 'os-cancelled'
    return 'os-open'
  }

  if (loading) return <div className="trade-empty">Loading orders…</div>
  if (err) return <div className="trade-empty err">{err}</div>
  if (!orders.length) return <div className="trade-empty">No orders today.</div>

  return (
    <div className="trade-table-wrap">
      <table className="trade-table">
        <thead>
          <tr>
            <th>Symbol</th><th>Type</th><th>Qty</th>
            <th className="num">Price</th><th className="num">Avg</th>
            <th>Status</th><th></th>
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => (
            <tr key={o.order_id}>
              <td>
                <span className={`txn-badge ${o.transaction_type === 'BUY' ? 'buy' : 'sell'}`}>
                  {o.transaction_type}
                </span>
                {' '}<strong>{o.symbol}</strong>
              </td>
              <td><small>{o.order_type} · {o.product}</small></td>
              <td>{o.filled_quantity}/{o.quantity}</td>
              <td className="num">{fmt(o.price)}</td>
              <td className="num">{o.average_price ? fmt(o.average_price) : '—'}</td>
              <td>
                <span className={`order-status ${statusClass(o.status)}`}>{o.status}</span>
                {o.message && <div className="order-msg">{o.message}</div>}
              </td>
              <td>
                {(o.status === 'OPEN' || o.status === 'TRIGGER_PENDING') && (
                  <button className="cancel-btn" onClick={() => cancel(o.order_id)}>✕</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function PositionsTab({ refresh }: { refresh: number }) {
  const [positions, setPositions] = useState<Positions | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setLoading((l) => l || positions === null)
    getPositions().then(setPositions).catch(() => {}).finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refresh])

  if (loading) return <div className="trade-empty">Loading positions…</div>
  const net = positions?.net ?? []
  if (!net.length) return <div className="trade-empty">No open positions.</div>

  return (
    <div className="trade-table-wrap">
      <table className="trade-table">
        <thead>
          <tr>
            <th>Symbol</th><th>Product</th>
            <th className="num">Qty</th><th className="num">Avg Price</th>
            <th className="num">LTP</th><th className="num">P&amp;L</th>
          </tr>
        </thead>
        <tbody>
          {net.map((p: Position) => (
            <tr key={`${p.symbol}-${p.product}`}>
              <td><strong>{p.symbol}</strong><br /><small className="muted">{p.exchange}</small></td>
              <td><small>{p.product}</small></td>
              <td className="num">{p.quantity}</td>
              <td className="num">{fmt(p.average_price)}</td>
              <td className="num">{fmt(p.last_price)}</td>
              <td className={`num ${pnlClass(p.pnl)}`}>{p.pnl >= 0 ? '+' : ''}{fmt(p.pnl)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function HoldingsTab({ refresh }: { refresh: number }) {
  const [holdings, setHoldings] = useState<Holding[] | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setLoading((l) => l || holdings === null)
    getHoldings().then(setHoldings).catch(() => setHoldings([])).finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refresh])

  if (loading || holdings === null) return <div className="trade-empty">Loading holdings…</div>
  if (!holdings.length) return <div className="trade-empty">No holdings.</div>

  return (
    <div className="trade-table-wrap">
      <table className="trade-table">
        <thead>
          <tr>
            <th>Symbol</th><th className="num">Qty</th><th className="num">Avg Price</th>
            <th className="num">LTP</th><th className="num">P&amp;L</th><th className="num">%</th>
          </tr>
        </thead>
        <tbody>
          {holdings.map((h) => (
            <tr key={h.symbol}>
              <td><strong>{h.symbol}</strong><br /><small className="muted">{h.exchange}</small></td>
              <td className="num">{h.quantity}</td>
              <td className="num">{fmt(h.average_price)}</td>
              <td className="num">{fmt(h.last_price)}</td>
              <td className={`num ${pnlClass(h.pnl)}`}>{h.pnl >= 0 ? '+' : ''}{fmt(h.pnl)}</td>
              <td className={`num ${pnlClass(h.pnl_percent)}`}>
                {h.pnl_percent >= 0 ? '+' : ''}{fmt(h.pnl_percent)}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** The simulated account's settings, today's result and a reset. */
function PaperAccountCard({ refresh, onReset }: { refresh: number; onReset: () => void }) {
  const [acct, setAcct] = useState<PaperAccount | null>(null)
  const [cash, setCash] = useState('')
  const [msg, setMsg] = useState<string | null>(null)

  useEffect(() => { getPaperAccount().then(setAcct).catch(() => {}) }, [refresh])

  const reset = async () => {
    const amount = cash.trim() ? Number(cash) : acct?.startingCash
    if (!window.confirm(`Reset the paper account to ₹${fmt(amount ?? 0)}? All paper orders, positions and holdings are deleted.`)) return
    try {
      setAcct(await resetPaperAccount(amount))
      setCash('')
      setMsg('Paper account reset')
      onReset()
    } catch (e) {
      setMsg(apiErrorMessage(e, 'Reset failed'))
    }
  }

  if (!acct) return null
  return (
    <div className="paper-card">
      <div className="paper-head">
        <strong>Paper trading</strong>
        <span className={`paper-market ${acct.marketOpen ? 'open' : ''}`}>
          {acct.marketOpen ? 'fills live' : 'market closed · orders queue for the open'}
        </span>
      </div>
      <div className="paper-stats">
        <span>Started with <strong>₹{fmt(acct.startingCash)}</strong></span>
        <span>Realised today <strong className={pnlClass(acct.realizedToday)}>₹{fmt(acct.realizedToday)}</strong></span>
        <span>Charges today <strong>₹{fmt(acct.chargesToday)}</strong></span>
      </div>
      <div className="paper-rules">
        Market fills at the live price ± {acct.slippagePct}% slippage · brokerage &amp; taxes charged ·
        MIS {acct.misLeverage}× leverage, squared off at {acct.squareOffTime} IST · CNC buys become holdings next day
      </div>
      <div className="paper-reset">
        <input className="of-input" type="number" min="1" placeholder={`Starting cash (₹${fmt(acct.startingCash)})`}
               value={cash} onChange={(e) => setCash(e.target.value)} />
        <button className="panel-btn" onClick={reset}>Reset paper account</button>
        {msg && <span className="ci-status ok">{msg}</span>}
      </div>
    </div>
  )
}

function FundsTab({ refresh }: { refresh: number }) {
  const [funds, setFunds] = useState<Funds | null>(null)
  const [loading, setLoading] = useState(false)
  const [paper, setPaper] = useState(false)

  const load = useCallback(() => {
    getFunds().then(setFunds).catch(() => {}).finally(() => setLoading(false))
    getTradingBroker().then((b) => setPaper(b.active === 'mock')).catch(() => {})
  }, [])

  useEffect(() => {
    setLoading((l) => l || funds === null)
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, refresh])

  if (loading) return <div className="trade-empty">Loading funds…</div>
  if (!funds) return <div className="trade-empty">No fund data.</div>

  return (
    <>
    {paper && <PaperAccountCard refresh={refresh} onReset={load} />}
    <div className="funds-grid">
      <div className="funds-card">
        <div className="funds-label">Available Cash</div>
        <div className="funds-value">₹{fmt(funds.available_cash)}</div>
      </div>
      <div className="funds-card">
        <div className="funds-label">Available Margin</div>
        <div className="funds-value">₹{fmt(funds.available_margin)}</div>
      </div>
      <div className="funds-card">
        <div className="funds-label">Used Margin</div>
        <div className="funds-value used">₹{fmt(funds.used_margin)}</div>
      </div>
      <div className="funds-card">
        <div className="funds-label">Total Balance</div>
        <div className="funds-value total">₹{fmt(funds.total_balance)}</div>
      </div>
    </div>
    </>
  )
}

type LimitKey = 'maxQuantityPerOrder' | 'maxOrderValue' | 'maxOrdersPerDay' | 'maxDailyLoss'

const LIMIT_FIELDS: { key: LimitKey; label: string; hint: string }[] = [
  { key: 'maxQuantityPerOrder', label: 'Max quantity / order', hint: 'shares' },
  { key: 'maxOrderValue', label: 'Max order value (₹)', hint: 'price × qty; market orders use last price' },
  { key: 'maxOrdersPerDay', label: 'Max orders / day', hint: 'counted from the broker order book' },
  { key: 'maxDailyLoss', label: 'Daily loss limit (₹)', hint: 'realised + open P&L; then only exits allowed' },
]

export function RiskTab() {
  const [state, setState] = useState<RiskState | null>(null)
  const [draft, setDraft] = useState<Record<LimitKey, string>>({
    maxQuantityPerOrder: '', maxOrderValue: '', maxOrdersPerDay: '', maxDailyLoss: '',
  })
  const [flags, setFlags] = useState({ requireConfirmation: true, applyToPaper: true })
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)

  const apply = (s: RiskState) => {
    setState(s)
    const v = (n: number | null) => (n == null ? '' : String(n))
    setDraft({
      maxQuantityPerOrder: v(s.settings.maxQuantityPerOrder),
      maxOrderValue: v(s.settings.maxOrderValue),
      maxOrdersPerDay: v(s.settings.maxOrdersPerDay),
      maxDailyLoss: v(s.settings.maxDailyLoss),
    })
    setFlags({ requireConfirmation: s.settings.requireConfirmation, applyToPaper: s.settings.applyToPaper })
  }

  const load = useCallback(() => {
    getRiskState().then(apply).catch((e) => setMsg({ ok: false, text: apiErrorMessage(e) }))
  }, [])
  useEffect(() => { load() }, [load])

  const save = async () => {
    if (!state) return
    const num = (x: string) => (x.trim() === '' ? null : Number(x))
    const settings: RiskSettings = {
      killSwitch: state.settings.killSwitch,
      maxQuantityPerOrder: num(draft.maxQuantityPerOrder),
      maxOrderValue: num(draft.maxOrderValue),
      maxOrdersPerDay: num(draft.maxOrdersPerDay),
      maxDailyLoss: num(draft.maxDailyLoss),
      ...flags,
    }
    try {
      apply(await updateRiskSettings(settings))
      setMsg({ ok: true, text: 'Limits saved' })
    } catch (e) {
      setMsg({ ok: false, text: apiErrorMessage(e, 'Could not save limits') })
    }
  }

  const toggleKill = async (on: boolean) => {
    if (on && !window.confirm('Turn the kill switch ON and cancel all open orders? '
      + 'Only orders that reduce existing positions will be allowed until you turn it off.')) return
    try {
      const r = await setKillSwitch(on, on)
      apply(r.state)
      const errors = r.errors ?? []
      setMsg({
        ok: errors.length === 0,
        text: on ? `Kill switch ON — cancelled ${r.cancelled.length} open order(s)${errors.length ? ` · ${errors.join(' · ')}` : ''}`
          : 'Kill switch off',
      })
    } catch (e) {
      setMsg({ ok: false, text: apiErrorMessage(e) })
    }
  }

  if (!state) return <div className="trade-empty">{msg?.text ?? 'Loading…'}</div>
  const kill = state.settings.killSwitch
  return (
    <div className="risk-tab">
      <div className={`kill-switch ${kill ? 'on' : ''}`}>
        <div>
          <div className="kill-title">Kill switch: {kill ? 'ON' : 'off'}</div>
          <div className="kill-sub">
            {kill ? 'New positions are blocked; exits are still allowed.'
              : 'Stops all new exposure and cancels open orders.'}
          </div>
        </div>
        <button className={`kill-btn ${kill ? '' : 'danger'}`} onClick={() => toggleKill(!kill)}>
          {kill ? 'Turn off' : '⛔ Stop trading'}
        </button>
      </div>

      <div className="risk-today">
        Broker <strong>{state.broker}</strong> · orders today <strong>{state.ordersToday}</strong>
        {state.dayPnl != null && <> · day P&L <strong className={pnlClass(state.dayPnl)}>₹{fmt(state.dayPnl)}</strong></>}
        {state.lossLimitReached && <span className="risk-hit"> · loss limit reached</span>}
      </div>

      {LIMIT_FIELDS.map((f) => (
        <div className="of-row" key={f.key}>
          <label title={f.hint}>{f.label}</label>
          <input className="of-input" type="number" min="0" placeholder="no limit" value={draft[f.key]}
                 onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))} />
        </div>
      ))}
      <label className="risk-check">
        <input type="checkbox" checked={flags.requireConfirmation}
               onChange={(e) => setFlags((f) => ({ ...f, requireConfirmation: e.target.checked }))} />
        Confirm every live order before it is sent
      </label>
      <label className="risk-check">
        <input type="checkbox" checked={flags.applyToPaper}
               onChange={(e) => setFlags((f) => ({ ...f, applyToPaper: e.target.checked }))} />
        Apply limits to paper (mock) trading too
      </label>
      <div className="of-actions">
        <button className="of-submit buy" onClick={save}>Save limits</button>
        <button className="panel-btn" onClick={load}>Refresh</button>
        {msg && <span className={`ci-status ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</span>}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Main TradingPanel — full mode OR compact (chart-side) mode
// ---------------------------------------------------------------------------

const FULL_TABS: { id: TradingTab; label: string }[] = [
  { id: 'order', label: 'Place Order' },
  { id: 'orders', label: 'Orders' },
  { id: 'positions', label: 'Positions' },
  { id: 'holdings', label: 'Holdings' },
  { id: 'funds', label: 'Funds' },
  { id: 'risk', label: 'Risk' },
]

interface TradingPanelProps {
  /** When true, shows a compact order-form + mini order list beside the chart. */
  compact?: boolean
  /** Current chart symbol — pre-fills the order form. */
  defaultSymbol?: string
  /** Whether chart replay mode is active. */
  replayMode?: boolean
  /** Current replay bar's epoch-seconds timestamp. */
  replayBarTime?: number
  /** Current replay bar's close price. */
  replayBarPrice?: number
  /** Called after a successful order so the chart can add a marker. */
  onTradeMarker?: (marker: TradeMarker) => void
}

export default function TradingPanel({
  compact,
  defaultSymbol,
  replayMode,
  replayBarTime,
  replayBarPrice,
  onTradeMarker,
}: TradingPanelProps) {
  const [tab, setTab] = useState<TradingTab>('order')
  const [orderRefresh, setOrderRefresh] = useState(0)

  // Paper orders fill on ticks, long after they were placed: refresh the lists when they do.
  useEffect(() => subscribePaper(() => setOrderRefresh((n) => n + 1)), [])

  const handleOrderPlaced = (marker: TradeMarker) => {
    setOrderRefresh((n) => n + 1)
    onTradeMarker?.(marker)
  }

  // ---------- Compact (chart-side) mode ----------
  if (compact) {
    return (
      <div className="trade-compact">
        <div className="trade-compact-title">Quick Trade</div>
        <OrderForm
          onOrderPlaced={handleOrderPlaced}
          defaultSymbol={defaultSymbol}
          replayMode={replayMode}
          replayBarTime={replayBarTime}
          replayBarPrice={replayBarPrice}
          compact
        />
        <div className="trade-compact-orders-label">Recent orders</div>
        <MiniOrderList refresh={orderRefresh} />
      </div>
    )
  }

  // ---------- Full mode ----------
  return (
    <div className="trading-panel">
      <nav className="trade-tabs">
        {FULL_TABS.map((t) => (
          <button
            key={t.id}
            className={`trade-tab ${tab === t.id ? 'active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>
      <div className="trade-body">
        {tab === 'order' && (
          <OrderForm
            onOrderPlaced={(m) => { setOrderRefresh((n) => n + 1); onTradeMarker?.(m) }}
            defaultSymbol={defaultSymbol}
            replayMode={replayMode}
            replayBarTime={replayBarTime}
            replayBarPrice={replayBarPrice}
          />
        )}
        {tab === 'orders' && <OrderBook refresh={orderRefresh} />}
        {tab === 'positions' && <PositionsTab refresh={orderRefresh} />}
        {tab === 'holdings' && <HoldingsTab refresh={orderRefresh} />}
        {tab === 'funds' && <FundsTab refresh={orderRefresh} />}
        {tab === 'risk' && <RiskTab />}
      </div>
    </div>
  )
}

