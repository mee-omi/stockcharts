import { useCallback, useEffect, useMemo, useState } from 'react'
import { apiError, deleteWatchlist, getQuotes, listWatchlists, saveWatchlist } from '../api/client'
import type { Quote, Watchlist } from '../api/types'
import { subscribeTicks } from '../ws/stomp'
import { num, pct, signClass } from '../lib/format'
import StockSearch from './StockSearch'

const POLL_MS = 10_000

interface Props {
  currentSymbol: string
  selected?: string
  onSelectList: (name: string) => void
  onPick: (symbol: string) => void
}

/** Watchlist with last price and day change, refreshed by polling plus live ticks. */
export default function WatchlistPanel({ currentSymbol, selected, onSelectList, onPick }: Props) {
  const [lists, setLists] = useState<Watchlist[]>([])
  const [quotes, setQuotes] = useState<Record<string, Quote>>({})
  const [newName, setNewName] = useState('')
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    listWatchlists().then((ls) => {
      setLists(ls)
      if (ls.length && (!selected || !ls.some((l) => l.name === selected))) onSelectList(ls[0].name)
    }).catch(() => setError('Could not load watchlists'))
  }, [selected, onSelectList])

  useEffect(() => { reload() }, [reload])

  const list = lists.find((l) => l.name === selected)
  const symbols = useMemo(() => list?.symbols ?? [], [list])
  const key = symbols.join(',')

  // Poll quotes (day change needs the previous close, which ticks don't carry).
  useEffect(() => {
    if (!symbols.length) return
    let stop = false
    const load = () => getQuotes(symbols)
      .then((qs) => !stop && setQuotes((prev) => {
        const next = { ...prev }
        qs.forEach((q) => { next[q.symbol] = q })
        return next
      }))
      .catch(() => {})
    load()
    const id = setInterval(load, POLL_MS)
    return () => { stop = true; clearInterval(id) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  // Live ticks move the last price between polls.
  useEffect(() => {
    const unsubs = symbols.map((s) => subscribeTicks(s, (u) => setQuotes((prev) => {
      const q = prev[s]
      if (!q) return prev
      const change = q.prevClose !== null ? u.ltp - q.prevClose : null
      return {
        ...prev,
        [s]: {
          ...q,
          ltp: u.ltp,
          change,
          changePct: change !== null && q.prevClose ? (change / q.prevClose) * 100 : null,
        },
      }
    })))
    return () => unsubs.forEach((u) => u())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const persist = async (wl: Watchlist) => {
    try {
      await saveWatchlist(wl)
      setError(null)
      reload()
    } catch (e) {
      setError(apiError(e, 'Could not save watchlist'))
    }
  }

  const addSymbol = (sym: string) => {
    if (!list || list.symbols.includes(sym)) return
    persist({ name: list.name, symbols: [...list.symbols, sym] })
  }

  const removeSymbol = (sym: string) => {
    if (!list) return
    persist({ name: list.name, symbols: list.symbols.filter((s) => s !== sym) })
  }

  const createList = async () => {
    const name = newName.trim()
    if (!name) return
    await persist({ name, symbols: currentSymbol ? [currentSymbol] : [] })
    setNewName('')
    onSelectList(name)
  }

  const removeList = async () => {
    if (!list || !window.confirm(`Delete watchlist "${list.name}"?`)) return
    await deleteWatchlist(list.name).catch(() => {})
    onSelectList('')
    reload()
  }

  return (
    <div className="watchlist-panel">
      <div className="wl-head">
        <select value={selected ?? ''} onChange={(e) => onSelectList(e.target.value)} aria-label="Watchlist">
          {!lists.length && <option value="">No watchlists</option>}
          {lists.map((l) => <option key={l.name} value={l.name}>{l.name} ({l.symbols.length})</option>)}
        </select>
        {list && <button className="icon-btn" onClick={removeList} title="Delete watchlist" aria-label="Delete watchlist">🗑</button>}
      </div>

      {list && (
        <div className="wl-add">
          <StockSearch compact clearOnSelect placeholder="Add symbol…" ariaLabel="Add symbol to watchlist"
                       onSelect={addSymbol} />
          {currentSymbol && !list.symbols.includes(currentSymbol) && (
            <button className="link-btn" onClick={() => addSymbol(currentSymbol)}>+ {currentSymbol}</button>
          )}
        </div>
      )}

      <div className="wl-new">
        <input placeholder="New list name" value={newName} onChange={(e) => setNewName(e.target.value)}
               onKeyDown={(e) => e.key === 'Enter' && createList()} aria-label="New watchlist name" />
        <button onClick={createList} disabled={!newName.trim()}>Create</button>
      </div>

      {error && <div className="wl-error">{error}</div>}

      <table className="wl-table">
        <tbody>
          {symbols.map((s) => {
            const q = quotes[s]
            return (
              <tr key={s} className={s === currentSymbol ? 'current' : ''} onClick={() => onPick(s)}>
                <td className="wl-sym">
                  {s}
                  {q?.type === 'INDEX' && <span className="badge pane">idx</span>}
                </td>
                <td className="num">{num(q?.ltp)}</td>
                <td className={`num ${signClass(q?.changePct)}`}>{pct(q?.changePct)}</td>
                <td>
                  <button className="icon-btn" aria-label={`Remove ${s}`}
                          onClick={(e) => { e.stopPropagation(); removeSymbol(s) }}>✕</button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {list && !symbols.length && <div className="muted wl-empty">Add symbols to track them here.</div>}
    </div>
  )
}
