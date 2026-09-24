import { useCallback, useEffect, useRef, useState } from 'react'
import StockSearch from './components/StockSearch'
import TimeframeBar from './components/TimeframeBar'
import IndicatorPanel from './components/IndicatorPanel'
import Chart from './components/Chart'
import ChartToolbar from './components/ChartToolbar'
import ScreenerView from './components/ScreenerView'
import TradingPanel from './components/TradingPanel'
import CustomIndicatorManager from './components/CustomIndicatorManager'
import DataSourceModal from './components/DataSourceModal'
import BrokerConfigModal from './components/BrokerConfigModal'
import ReplayControls from './components/ReplayControls'
import WatchlistPanel from './components/WatchlistPanel'
import AlertsView from './components/AlertsView'
import AlertToaster from './components/AlertToaster'
import BacktestView from './components/BacktestView'
import PortfolioView from './components/PortfolioView'
import { UserMenu } from './components/AuthGate'
import MarketStatusChip from './components/MarketStatusChip'
import {
  applyIndicators,
  getCandles,
  getDataSource,
  getIndicatorCatalog,
  getTradingBroker,
  getUiState,
  putUiState,
} from './api/client'
import type {
  ApplyResponse,
  Candle,
  CatalogEntry,
  ChartLayout,
  ChartType,
  DataSourceInfo,
  Drawing,
  DrawingTool,
  IndicatorSpec,
  Timeframe,
  TradeMarker,
  TradingBrokerInfo,
} from './api/types'
import { olderRangeStart } from './lib/chartMath'

type View = 'chart' | 'screener' | 'backtest' | 'alerts' | 'portfolio' | 'trading'

const VIEWS: { id: View; label: string }[] = [
  { id: 'chart', label: 'Chart' },
  { id: 'screener', label: 'Screener' },
  { id: 'backtest', label: 'Backtest' },
  { id: 'alerts', label: 'Alerts' },
  { id: 'portfolio', label: 'Portfolio' },
  { id: 'trading', label: 'Trade' },
]

const LAYOUT_KEY = 'layout'
/** Bars fetched per "More history" click. */
const MORE_BARS = 500

export default function App() {
  const [view, setView] = useState<View>('chart')
  const [symbol, setSymbol] = useState<string>('')
  const [timeframe, setTimeframe] = useState<Timeframe>('1m')
  const [catalog, setCatalog] = useState<CatalogEntry[]>([])
  const [indicators, setIndicators] = useState<IndicatorSpec[]>([])
  const [chartType, setChartType] = useState<ChartType>('candles')
  const [data, setData] = useState<ApplyResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [panelOpen, setPanelOpen] = useState(false)
  const [watchOpen, setWatchOpen] = useState(false)
  const [watchlist, setWatchlist] = useState<string | undefined>(undefined)
  const [tradeOpen, setTradeOpen] = useState(false)
  const [tradeMarkers, setTradeMarkers] = useState<TradeMarker[]>([])
  const [customOpen, setCustomOpen] = useState(false)
  const [dsOpen, setDsOpen] = useState(false)
  const [brokerOpen, setBrokerOpen] = useState(false)
  const [dataSource, setDataSource] = useState<string>('')
  const [broker, setBroker] = useState<string>('')
  const [replayOn, setReplayOn] = useState(false)
  const [replayCount, setReplayCount] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(5)
  // Extra history requested via "More history", valid only for the chart it was requested on.
  const [extraRange, setExtraRange] = useState<{ key: string; from: number } | null>(null)
  const [compareSymbol, setCompareSymbol] = useState<string | null>(null)
  const [compareCandles, setCompareCandles] = useState<Candle[]>([])
  const [drawings, setDrawings] = useState<Drawing[]>([])
  const [tool, setTool] = useState<DrawingTool>('none')
  const [drawHint, setDrawHint] = useState<string | null>(null)
  const [layoutLoaded, setLayoutLoaded] = useState(false)
  // Markers to show once the chart for a backtest's symbol/timeframe has been selected.
  const pendingMarkers = useRef<TradeMarker[] | null>(null)

  const total = data?.candles.length ?? 0
  const chartKey = `${symbol}|${timeframe}`
  const rangeFrom = extraRange?.key === chartKey ? extraRange.from : undefined

  const enterReplay = () => {
    if (!total) return
    setReplayOn(true)
    setPlaying(false)
    setReplayCount(Math.max(10, Math.floor(total * 0.5)))
  }
  const exitReplay = () => { setReplayOn(false); setPlaying(false) }
  const stepReplay = (d: number) => setReplayCount((c) => Math.min(total, Math.max(1, c + d)))

  const reloadCatalog = useCallback(() => {
    getIndicatorCatalog().then(setCatalog).catch(() => setError('Failed to load indicator catalog'))
  }, [])

  useEffect(() => { reloadCatalog() }, [reloadCatalog])
  useEffect(() => { getDataSource().then((i) => setDataSource(i.active)).catch(() => {}) }, [])
  useEffect(() => { getTradingBroker().then((i) => setBroker(i.active)).catch(() => {}) }, [])

  // Restore the saved layout (symbol, timeframe, indicators, chart type, watchlist).
  useEffect(() => {
    getUiState<ChartLayout>(LAYOUT_KEY)
      .then((l) => {
        if (!l) return
        if (l.timeframe) setTimeframe(l.timeframe)
        if (Array.isArray(l.indicators)) setIndicators(l.indicators)
        if (l.chartType) setChartType(l.chartType)
        if (l.watchlist) { setWatchlist(l.watchlist); setWatchOpen(true) }
        if (l.symbol) setSymbol(l.symbol)
      })
      .catch(() => {})
      .finally(() => setLayoutLoaded(true))
  }, [])

  // Persist the layout (debounced) once the saved one has been applied.
  useEffect(() => {
    if (!layoutLoaded) return
    const id = setTimeout(() => {
      putUiState<ChartLayout>(LAYOUT_KEY, { symbol, timeframe, indicators, chartType, watchlist }).catch(() => {})
    }, 800)
    return () => clearTimeout(id)
  }, [layoutLoaded, symbol, timeframe, indicators, chartType, watchlist])

  const onSourceSwitched = (info: DataSourceInfo) => {
    setDataSource(info.active)
    reloadCatalog()
    // Instrument universe changed — clear the current selection so the user re-picks.
    setSymbol('')
    setData(null)
    exitReplay()
  }

  const onBrokerSwitched = (info: TradingBrokerInfo) => {
    setBroker(info.active)
  }

  // New symbol/timeframe: reset trade markers (or show the ones a backtest queued).
  useEffect(() => {
    setTradeMarkers(pendingMarkers.current ?? [])
    pendingMarkers.current = null
  }, [symbol, timeframe])

  useEffect(() => { setCompareSymbol(null) }, [symbol])

  // Drawings are saved per symbol.
  useEffect(() => {
    setDrawings([])
    setTool('none')
    if (!symbol) return
    let cancelled = false
    getUiState<{ items: Drawing[] }>(`drawings:${symbol}`)
      .then((d) => !cancelled && setDrawings(d?.items ?? []))
      .catch(() => {})
    return () => { cancelled = true }
  }, [symbol])

  const saveDrawings = (items: Drawing[]) => {
    setDrawings(items)
    if (symbol) putUiState(`drawings:${symbol}`, { items }).catch(() => {})
  }

  const addTradeMarker = (marker: TradeMarker) => {
    setTradeMarkers((prev) => [...prev, marker])
  }

  // Compute the current replay bar for pre-filling the order form.
  const currentReplayBar = replayOn && data
    ? data.candles[Math.min(replayCount, total) - 1] ?? null
    : null

  // Keep the replay position within the (possibly changed) dataset.
  useEffect(() => {
    if (!data) return
    setReplayCount((c) => (c === 0 ? data.candles.length : Math.min(Math.max(1, c), data.candles.length)))
  }, [data])

  // Auto-advance while playing.
  useEffect(() => {
    if (!playing || !replayOn) return
    const id = setInterval(() => {
      setReplayCount((c) => {
        if (c >= total) { setPlaying(false); return c }
        return c + 1
      })
    }, 1000 / speed)
    return () => clearInterval(id)
  }, [playing, replayOn, speed, total])

  useEffect(() => {
    if (!symbol) return
    let cancelled = false
    setLoading(true)
    setError(null)
    applyIndicators(symbol, timeframe, indicators, rangeFrom ? { from: rangeFrom, to: Date.now() } : undefined)
      .then((res) => !cancelled && setData(res))
      .catch(() => !cancelled && setError('Failed to load chart data'))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [symbol, timeframe, indicators, rangeFrom])

  // Compare overlay candles over the same range as the main chart.
  const firstTime = data?.candles[0]?.time
  useEffect(() => {
    if (!compareSymbol || !firstTime) { setCompareCandles([]); return }
    let cancelled = false
    getCandles(compareSymbol, timeframe, { from: firstTime * 1000, to: Date.now() })
      .then((c) => !cancelled && setCompareCandles(c))
      .catch(() => !cancelled && setError(`Failed to load ${compareSymbol}`))
    return () => { cancelled = true }
  }, [compareSymbol, timeframe, firstTime])

  const loadMore = () => {
    if (!firstTime) return
    setExtraRange({ key: chartKey, from: olderRangeStart(firstTime, timeframe, MORE_BARS) })
  }

  const pickSymbol = (s: string) => {
    setSymbol(s)
    setView('chart')
  }

  const showBacktestOnChart = (s: string, tf: Timeframe, markers: TradeMarker[]) => {
    // Load enough history to cover the first trade, so every marker lands on a bar.
    const earliest = markers.reduce((m, x) => Math.min(m, x.time), Infinity)
    if (Number.isFinite(earliest)) {
      setExtraRange({ key: `${s}|${tf}`, from: olderRangeStart(earliest, tf, 20) })
    }
    if (s === symbol && tf === timeframe) setTradeMarkers(markers)
    else pendingMarkers.current = markers
    setSymbol(s)
    setTimeframe(tf)
    setView('chart')
  }

  const compare = compareSymbol && compareCandles.length ? { symbol: compareSymbol, candles: compareCandles } : null

  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <span className="logo">📈</span>
          <span className="brand-name">StockCharts</span>
        </div>
        <nav className="view-nav" aria-label="Views">
          {VIEWS.map((v) => (
            <button key={v.id} className={view === v.id ? 'active' : ''} onClick={() => setView(v.id)}
                    aria-current={view === v.id ? 'page' : undefined}>
              {v.label}
            </button>
          ))}
        </nav>
        {view === 'chart' && (
          <div className="search-box">
            <StockSearch onSelect={setSymbol} />
          </div>
        )}
        <button className="panel-btn datasource-btn" onClick={() => setDsOpen(true)}
                title="Switch data source">
          <span className={`ds-dot ${dataSource === 'mock' ? 'mock' : 'live'}`} />
          {dataSource || 'source'}
        </button>
        <button className="panel-btn datasource-btn broker-btn" onClick={() => setBrokerOpen(true)}
                title="Configure trading broker">
          <span className={`ds-dot ${broker === 'mock' ? 'mock' : 'live'}`} />
          {broker ? `broker: ${broker}` : 'broker'}
        </button>
        <button className="panel-btn" onClick={() => setCustomOpen(true)}>ƒx Custom</button>
        {view === 'chart' && (
          <>
            <button
              className={`panel-btn ${watchOpen ? 'active' : ''}`}
              onClick={() => setWatchOpen((o) => !o)}
            >
              ☰ Watchlist
            </button>
            <button
              className={`panel-btn ${panelOpen ? 'active' : ''}`}
              onClick={() => setPanelOpen((o) => !o)}
            >
              Indicators{indicators.length ? ` (${indicators.length})` : ''}
            </button>
            <button
              className={`panel-btn trade-toggle-btn ${tradeOpen ? 'active' : ''}`}
              onClick={() => setTradeOpen((o) => !o)}
              title="Quick trade panel"
            >
              ⚡ Trade
            </button>
          </>
        )}
        <UserMenu />
      </header>

      {view === 'chart' ? (
        <>
          <div className="sub-header">
            <div className="symbol-label">
              {symbol ? (
                <>
                  <strong>{symbol}</strong>
                  <span className="exchange">{timeframe}</span>
                </>
              ) : (
                <span className="hint">Search and select a stock to begin</span>
              )}
              <MarketStatusChip />
            </div>
            <div className="sub-header-right">
              <TimeframeBar value={timeframe} onChange={setTimeframe} />
              <button
                className={`tf-btn replay-toggle ${replayOn ? 'active' : ''}`}
                onClick={replayOn ? exitReplay : enterReplay}
                disabled={!symbol}
                title="Bar replay for backtesting"
              >
                ⏵ Replay
              </button>
              <button className="tf-btn" disabled={!symbol} title="Create an alert for this symbol"
                      onClick={() => setView('alerts')}>
                🔔 Alert
              </button>
            </div>
          </div>

          <ChartToolbar
            chartType={chartType}
            onChartType={setChartType}
            compareSymbol={compareSymbol}
            onCompare={setCompareSymbol}
            tool={tool}
            onTool={setTool}
            drawingCount={drawings.length}
            onUndoDrawing={() => saveDrawings(drawings.slice(0, -1))}
            onClearDrawings={() => saveDrawings([])}
            hint={drawHint}
            onLoadMore={loadMore}
            loadingMore={loading && !!rangeFrom}
            disabled={!symbol || !data}
          />

          {replayOn && symbol && (
            <ReplayControls
              total={total}
              count={replayCount}
              playing={playing}
              speed={speed}
              currentTime={data?.candles[Math.min(replayCount, total) - 1]?.time}
              onCount={setReplayCount}
              onPlayToggle={() => setPlaying((p) => !p)}
              onStep={stepReplay}
              onSpeed={setSpeed}
              onExit={exitReplay}
            />
          )}

          <main className="content">
            {watchOpen && (
              <aside className="side-panel left-panel">
                <WatchlistPanel
                  currentSymbol={symbol}
                  selected={watchlist}
                  onSelectList={setWatchlist}
                  onPick={setSymbol}
                />
              </aside>
            )}

            <section className="chart-area">
              {error && <div className="banner error">{error}</div>}
              {loading && <div className="banner">Loading…</div>}
              <Chart
                symbol={symbol}
                timeframe={timeframe}
                data={data}
                visibleCount={replayOn ? replayCount : undefined}
                replayActive={replayOn}
                tradeMarkers={tradeMarkers}
                chartType={chartType}
                compare={compare}
                drawings={drawings}
                drawingTool={tool}
                onAddDrawing={(d) => saveDrawings([...drawings, d])}
                onDrawingHint={setDrawHint}
              />
            </section>

            {panelOpen && (
              <aside className="side-panel">
                <IndicatorPanel catalog={catalog} selected={indicators} onChange={setIndicators} />
              </aside>
            )}

            {tradeOpen && (
              <aside className="side-panel trade-side-panel">
                <TradingPanel
                  compact
                  defaultSymbol={symbol}
                  replayMode={replayOn}
                  replayBarTime={currentReplayBar?.time}
                  replayBarPrice={currentReplayBar?.close}
                  onTradeMarker={addTradeMarker}
                />
              </aside>
            )}
          </main>
        </>
      ) : view === 'screener' ? (
        <main className="content">
          <ScreenerView onPickSymbol={pickSymbol} />
        </main>
      ) : view === 'backtest' ? (
        <main className="content scroll">
          <BacktestView defaultSymbol={symbol} onShowOnChart={showBacktestOnChart} />
        </main>
      ) : view === 'alerts' ? (
        <main className="content scroll">
          <AlertsView defaultSymbol={symbol} onPickSymbol={pickSymbol} />
        </main>
      ) : view === 'portfolio' ? (
        <main className="content scroll">
          <PortfolioView />
        </main>
      ) : (
        <main className="content">
          <TradingPanel />
        </main>
      )}

      <AlertToaster onOpen={pickSymbol} />

      {customOpen && (
        <CustomIndicatorManager
          onChanged={reloadCatalog}
          onClose={() => setCustomOpen(false)}
        />
      )}

      {dsOpen && (
        <DataSourceModal
          onSwitched={onSourceSwitched}
          onClose={() => setDsOpen(false)}
        />
      )}

      {brokerOpen && (
        <BrokerConfigModal
          onSwitched={onBrokerSwitched}
          onClose={() => setBrokerOpen(false)}
        />
      )}
    </div>
  )
}
