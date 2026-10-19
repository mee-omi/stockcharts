import { useCallback, useEffect, useState } from 'react'
import {
  apiErrorMessage,
  deleteScanSchedule,
  getScanRuns,
  listScanSchedules,
  runScanScheduleNow,
  updateScanSchedule,
} from '../api/client'
import type { ScanRun, ScanSchedule } from '../api/types'
import { istDateTime, pct } from '../lib/format'

interface Props {
  /** Bumped by the parent after it creates a schedule. */
  refresh: number
  onOpenSymbol: (symbol: string) => void
}

function RunRows({ run, onOpenSymbol }: { run: ScanRun; onOpenSymbol: (s: string) => void }) {
  if (run.status === 'FAILED') return <div className="err small">Failed: {run.message}</div>
  if (!run.rows.length) return <div className="muted small">No matches.</div>
  return (
    <table className="data-table scan-run-table">
      <thead><tr><th>Symbol</th><th>Signal</th><th className="num">Return</th><th className="num">Win</th><th className="num">Trades</th></tr></thead>
      <tbody>
        {run.rows.map((r) => (
          <tr key={r.symbol}>
            <td><button className="linklike" onClick={() => onOpenSymbol(r.symbol)}>{r.symbol}</button>
              {r.isNew && <span className="badge new-badge">new</span>}</td>
            <td><span className={`sig ${r.signal.toLowerCase()}`}>{r.signal.replace('_', ' ').toLowerCase()}</span></td>
            <td className="num">{pct(r.totalReturnPct, 1)}</td>
            <td className="num">{pct(r.winRatePct, 0).replace('+', '')}</td>
            <td className="num">{r.trades}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** Saved scans that run on an IST timetable, with their run history. */
export default function ScanSchedules({ refresh, onOpenSymbol }: Props) {
  const [items, setItems] = useState<ScanSchedule[] | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [history, setHistory] = useState<ScanRun[]>([])
  const [busy, setBusy] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    listScanSchedules().then(setItems).catch((e) => setError(apiErrorMessage(e, 'Could not load schedules')))
  }, [])
  useEffect(() => { load() }, [load, refresh])

  const showHistory = async (id: number) => {
    if (open === id) { setOpen(null); return }
    setOpen(id)
    setHistory(await getScanRuns(id).catch(() => []))
  }

  const act = async (id: number, fn: () => Promise<unknown>) => {
    setBusy(id)
    setError(null)
    try {
      await fn()
      load()
      if (open === id) setHistory(await getScanRuns(id).catch(() => []))
    } catch (e) {
      setError(apiErrorMessage(e, 'Action failed'))
    } finally {
      setBusy(null)
    }
  }

  if (!items) return <section className="card"><div className="muted">Loading schedules…</div></section>
  return (
    <section className="card">
      <h3>Scheduled scans</h3>
      {error && <div className="err">{error}</div>}
      {!items.length && (
        <div className="muted">No schedules yet. Set up a scan under “Scan universe” and use “Schedule this scan”.</div>
      )}
      {items.map((s) => (
        <div key={s.id} className={`schedule-row ${s.enabled ? '' : 'paused'}`}>
          <div className="schedule-main">
            <div>
              <strong>{s.name}</strong>
              <div className="muted small">
                {s.description} · {s.scan.universe.type === 'all' ? 'all symbols' : s.scan.universe.value} · {s.scan.strategy.timeframe}
                {s.onlyNew && ' · only new matches'} · top {s.topN}{s.notify ? '' : ' · notifications off'}
              </div>
              <div className="small">
                {s.enabled
                  ? (s.nextRunAt ? <>Next: {istDateTime(s.nextRunAt)} IST</> : 'No run in the next 30 days')
                  : 'Paused'}
                {s.lastRun && (
                  <> · Last: {istDateTime(s.lastRun.startedAt)} — {s.lastRun.status === 'OK'
                    ? `${s.lastRun.matched} matched, ${s.lastRun.newMatches} new`
                    : 'failed'}</>
                )}
              </div>
            </div>
            <div className="schedule-actions">
              <button className="panel-btn" disabled={busy === s.id}
                      onClick={() => act(s.id, () => runScanScheduleNow(s.id))}>
                {busy === s.id ? 'Running…' : 'Run now'}
              </button>
              <button className="panel-btn" onClick={() => act(s.id, () => updateScanSchedule(s.id, { enabled: !s.enabled }))}>
                {s.enabled ? 'Pause' : 'Resume'}
              </button>
              <button className="panel-btn" onClick={() => showHistory(s.id)} aria-expanded={open === s.id}>
                History
              </button>
              <button className="panel-btn" aria-label={`Delete ${s.name}`} onClick={() => {
                if (window.confirm(`Delete the schedule "${s.name}" and its history?`)) act(s.id, () => deleteScanSchedule(s.id))
              }}>✕</button>
            </div>
          </div>
          {open === s.id && (
            <div className="schedule-history">
              {!history.length && <div className="muted small">No runs yet.</div>}
              {history.map((r) => (
                <details key={r.id} open={r.id === history[0]?.id}>
                  <summary>
                    {istDateTime(r.startedAt)} IST — {r.status === 'OK'
                      ? `${r.matched} matched, ${r.newMatches} new` : 'failed'} · {(r.durationMs / 1000).toFixed(1)} s
                  </summary>
                  <RunRows run={r} onOpenSymbol={onOpenSymbol} />
                </details>
              ))}
            </div>
          )}
        </div>
      ))}
    </section>
  )
}
