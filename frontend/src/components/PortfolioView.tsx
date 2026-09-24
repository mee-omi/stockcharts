import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  addJournalEntry,
  annotateJournalEntry,
  apiError,
  deleteJournalEntry,
  getPortfolioReport,
  listJournal,
  portfolioCsvUrl,
  syncJournal,
} from '../api/client'
import type { JournalEntry, PnlBucket, PortfolioReport, ProductType, TransactionType } from '../api/types'
import { inr, istDate, istDateTime, num, signClass } from '../lib/format'
import EquityChart from './EquityChart'
import { Tile } from './BacktestView'

type Tab = 'report' | 'journal'

export default function PortfolioView() {
  const [tab, setTab] = useState<Tab>('report')
  return (
    <div className="page">
      <div className="trade-tabs page-tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'report'} className={`trade-tab ${tab === 'report' ? 'active' : ''}`}
                onClick={() => setTab('report')}>P&amp;L &amp; tax</button>
        <button role="tab" aria-selected={tab === 'journal'} className={`trade-tab ${tab === 'journal' ? 'active' : ''}`}
                onClick={() => setTab('journal')}>Journal</button>
      </div>
      {tab === 'report' ? <ReportTab /> : <JournalTab />}
    </div>
  )
}

// ---------------------------------------------------------------------------

function ReportTab() {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [broker, setBroker] = useState('')
  const [report, setReport] = useState<PortfolioReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  const params = useMemo(() => ({
    from: from ? new Date(`${from}T00:00:00+05:30`).getTime() : undefined,
    to: to ? new Date(`${to}T23:59:59+05:30`).getTime() : undefined,
    broker: broker || undefined,
  }), [from, to, broker])

  useEffect(() => {
    getPortfolioReport(params).then((r) => { setReport(r); setError(null) })
      .catch((e) => setError(apiError(e, 'Could not load report')))
  }, [params])

  const cumulative = useMemo(() => (report?.daily ?? []).map((d) => ({
    time: Math.floor(new Date(`${d.date}T00:00:00+05:30`).getTime() / 1000), value: d.cumulative,
  })), [report])
  const daily = useMemo(() => (report?.daily ?? []).map((d) => ({
    time: Math.floor(new Date(`${d.date}T00:00:00+05:30`).getTime() / 1000), value: d.netPnl,
  })), [report])

  const s = report?.summary
  return (
    <>
      <section className="card">
        <div className="control-row">
          <label>From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
          <label>Broker
            <select value={broker} onChange={(e) => setBroker(e.target.value)}>
              <option value="">All</option>
              <option value="mock">Paper (mock)</option>
              <option value="zerodha">Zerodha</option>
              <option value="angelone">Angel One</option>
              <option value="manual">Manual entries</option>
            </select>
          </label>
          <a className="run-btn secondary" href={portfolioCsvUrl(params)} download>Download CSV</a>
        </div>
        {error && <div className="err">{error}</div>}
        {s && (
          <div className="tiles">
            <Tile label="Net P&L" value={inr(s.netPnl)} cls={signClass(s.netPnl)} />
            <Tile label="Gross P&L" value={inr(s.grossPnl)} cls={signClass(s.grossPnl)} />
            <Tile label="Charges (est.)" value={inr(s.charges)} />
            <Tile label="Closed trades" value={String(s.trades)} />
            <Tile label="Win rate" value={`${num(s.winRatePct, 1)}%`} />
            <Tile label="Turnover" value={inr(s.turnover, 0)} />
          </div>
        )}
        {cumulative.length > 0
          ? <EquityChart points={cumulative} secondary={daily} secondaryKind="bars" height={220} />
          : <div className="muted">No closed trades in this period yet. Filled orders are journalled
              automatically; use Journal → “Import from broker” for older ones.</div>}
      </section>

      {report && report.realized.length > 0 && (
        <>
          <section className="card">
            <h3>By financial year — tax estimate</h3>
            <div className="table-wrap">
              <table className="results-table">
                <thead>
                  <tr><th>FY</th><th className="num">Intraday (net)</th><th className="num">STCG</th>
                    <th className="num">LTCG</th><th className="num">Est. STCG tax</th><th className="num">Est. LTCG tax</th></tr>
                </thead>
                <tbody>
                  {report.byFinancialYear.map((f) => (
                    <tr key={f.year}>
                      <td>{f.year}</td>
                      <td className={`num ${signClass(f.intradayNetPnl)}`}>{inr(f.intradayNetPnl)}</td>
                      <td className={`num ${signClass(f.stcg)}`}>{inr(f.stcg)}</td>
                      <td className={`num ${signClass(f.ltcg)}`}>{inr(f.ltcg)}</td>
                      <td className="num">{inr(f.estimatedStcgTax)}</td>
                      <td className="num">{inr(f.estimatedLtcgTax)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <div className="two-col">
            <BucketTable title="By category" rows={report.byCategory} />
            <BucketTable title="By symbol" rows={report.bySymbol} />
          </div>

          <section className="card">
            <h3>Closed trades ({report.realized.length})</h3>
            <div className="table-wrap">
              <table className="results-table">
                <thead>
                  <tr><th>Symbol</th><th>Side</th><th className="num">Qty</th><th>Opened</th><th className="num">Open</th>
                    <th>Closed</th><th className="num">Close</th><th>Type</th><th className="num">Charges</th>
                    <th className="num">Net</th></tr>
                </thead>
                <tbody>
                  {report.realized.slice().reverse().slice(0, 300).map((t, i) => (
                    <tr key={i}>
                      <td className="sym">{t.symbol}</td><td>{t.side}</td><td className="num">{t.quantity}</td>
                      <td>{istDate(t.openTime)}</td><td className="num">{num(t.openPrice)}</td>
                      <td>{istDate(t.closeTime)}</td><td className="num">{num(t.closePrice)}</td>
                      <td>{t.category}</td><td className="num">{num(t.charges)}</td>
                      <td className={`num ${signClass(t.netPnl)}`}>{num(t.netPnl)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}

      {report && report.openLots.length > 0 && (
        <section className="card">
          <h3>Open lots (from journal)</h3>
          <table className="results-table">
            <thead><tr><th>Symbol</th><th className="num">Qty</th><th className="num">Avg price</th><th>Since</th></tr></thead>
            <tbody>
              {report.openLots.map((l) => (
                <tr key={l.symbol}>
                  <td className="sym">{l.symbol}</td><td className="num">{l.quantity}</td>
                  <td className="num">{num(l.averagePrice)}</td><td>{istDate(l.since)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {report && (
        <details className="card assumptions">
          <summary>How this is calculated (estimates, not tax advice)</summary>
          <ul>{report.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>
        </details>
      )}
    </>
  )
}

function BucketTable({ title, rows }: { title: string; rows: PnlBucket[] }) {
  return (
    <section className="card">
      <h3>{title}</h3>
      <table className="results-table">
        <thead><tr><th /><th className="num">Trades</th><th className="num">Wins</th><th className="num">Charges</th><th className="num">Net</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <td className="sym">{r.key}</td><td className="num">{r.trades}</td><td className="num">{r.wins}</td>
              <td className="num">{num(r.charges)}</td><td className={`num ${signClass(r.netPnl)}`}>{num(r.netPnl)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

// ---------------------------------------------------------------------------

function JournalTab() {
  const [entries, setEntries] = useState<JournalEntry[]>([])
  const [status, setStatus] = useState<{ ok: boolean; msg: string } | null>(null)
  const [adding, setAdding] = useState(false)

  const reload = useCallback(() => {
    listJournal().then(setEntries).catch(() => setStatus({ ok: false, msg: 'Could not load journal' }))
  }, [])
  useEffect(() => { reload() }, [reload])

  const sync = async () => {
    try {
      const added = await syncJournal()
      setStatus({ ok: true, msg: `Imported ${added} new trade(s) from the broker` })
      reload()
    } catch (e) {
      setStatus({ ok: false, msg: apiError(e, 'Import failed') })
    }
  }

  const remove = async (e: JournalEntry) => {
    if (!window.confirm(`Delete ${e.side} ${e.quantity} ${e.symbol}?`)) return
    await deleteJournalEntry(e.id).catch(() => {})
    reload()
  }

  return (
    <>
      <section className="card">
        <div className="control-row">
          <button className="run-btn" onClick={sync}>Import from broker</button>
          <button className="run-btn secondary" onClick={() => setAdding((a) => !a)}>
            {adding ? 'Close' : '+ Add trade manually'}
          </button>
          {status && <span className={status.ok ? 'ok' : 'err'}>{status.msg}</span>}
        </div>
        {adding && <ManualEntryForm onSaved={() => { setAdding(false); reload() }} />}
      </section>

      <section className="card">
        <h3>Fills ({entries.length})</h3>
        {!entries.length ? <div className="muted">No trades journalled yet.</div> : (
          <div className="table-wrap">
            <table className="results-table">
              <thead>
                <tr><th>When (IST)</th><th>Symbol</th><th>Side</th><th>Product</th><th className="num">Qty</th>
                  <th className="num">Price</th><th>Broker</th><th>Strategy</th><th>Notes</th><th /></tr>
              </thead>
              <tbody>
                {entries.map((e) => <JournalRow key={e.id} entry={e} onDelete={() => remove(e)} />)}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function JournalRow({ entry, onDelete }: { entry: JournalEntry; onDelete: () => void }) {
  const [strategy, setStrategy] = useState(entry.strategy ?? '')
  const [notes, setNotes] = useState(entry.notes ?? '')
  const [dirty, setDirty] = useState(false)

  const save = async () => {
    await annotateJournalEntry(entry.id, { strategy, notes }).catch(() => {})
    setDirty(false)
  }

  return (
    <tr>
      <td>{istDateTime(entry.executedAt)}</td>
      <td className="sym">{entry.symbol}</td>
      <td><span className={`txn-badge ${entry.side === 'BUY' ? 'buy' : 'sell'}`}>{entry.side}</span></td>
      <td>{entry.product}</td>
      <td className="num">{entry.quantity}</td>
      <td className="num">{num(entry.price)}</td>
      <td>{entry.broker}{entry.tag ? ` · ${entry.tag}` : ''}</td>
      <td><input className="inline-input" value={strategy} placeholder="—" aria-label="Strategy"
                 onChange={(e) => { setStrategy(e.target.value); setDirty(true) }} onBlur={() => dirty && save()} /></td>
      <td><input className="inline-input wide" value={notes} placeholder="add a note" aria-label="Notes"
                 onChange={(e) => { setNotes(e.target.value); setDirty(true) }} onBlur={() => dirty && save()} /></td>
      <td><button className="icon-btn" onClick={onDelete} aria-label="Delete entry">✕</button></td>
    </tr>
  )
}

function ManualEntryForm({ onSaved }: { onSaved: () => void }) {
  const [symbol, setSymbol] = useState('')
  const [side, setSide] = useState<TransactionType>('BUY')
  const [product, setProduct] = useState<ProductType>('CNC')
  const [qty, setQty] = useState('')
  const [price, setPrice] = useState('')
  const [when, setWhen] = useState('')
  const [strategy, setStrategy] = useState('')
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    try {
      await addJournalEntry({
        symbol: symbol.trim(), side, product, quantity: Number(qty), price: Number(price),
        executedAt: when ? new Date(`${when}+05:30`).getTime() : undefined,
        strategy: strategy || undefined,
      })
      onSaved()
    } catch (e) {
      setError(apiError(e, 'Could not save'))
    }
  }

  return (
    <div className="form-grid manual-entry">
      <label>Symbol<input value={symbol} onChange={(e) => setSymbol(e.target.value.toUpperCase())} placeholder="INFY" /></label>
      <label>Side
        <select value={side} onChange={(e) => setSide(e.target.value as TransactionType)}>
          <option>BUY</option><option>SELL</option>
        </select>
      </label>
      <label>Product
        <select value={product} onChange={(e) => setProduct(e.target.value as ProductType)}>
          <option value="CNC">CNC (delivery)</option><option value="MIS">MIS (intraday)</option><option value="NRML">NRML</option>
        </select>
      </label>
      <label>Quantity<input type="number" value={qty} onChange={(e) => setQty(e.target.value)} /></label>
      <label>Price<input type="number" value={price} onChange={(e) => setPrice(e.target.value)} /></label>
      <label>Executed (IST)<input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} /></label>
      <label>Strategy<input value={strategy} onChange={(e) => setStrategy(e.target.value)} /></label>
      <div className="form-actions">
        <button className="run-btn" onClick={save}>Save trade</button>
        {error && <span className="err">{error}</span>}
      </div>
    </div>
  )
}
