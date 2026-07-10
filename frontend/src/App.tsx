import { useCallback, useEffect, useState } from 'react'
import StockSearch from './components/StockSearch'
import TimeframeBar from './components/TimeframeBar'
import IndicatorPanel from './components/IndicatorPanel'
import Chart from './components/Chart'
import ScreenerView from './components/ScreenerView'
import TradingPanel from './components/TradingPanel'
import CustomIndicatorManager from './components/CustomIndicatorManager'
import DataSourceModal from './components/DataSourceModal'
import BrokerConfigModal from './components/BrokerConfigModal'
import ReplayControls from './components/ReplayControls'
import { applyIndicators, getDataSource, getIndicatorCatalog, getTradingBroker } from './api/client'
import type {
  ApplyResponse,
  CatalogEntry,
  DataSourceInfo,
  IndicatorSpec,
  Timeframe,
  TradeMarker,
  TradingBrokerInfo,
} from './api/types'

type View = 'chart' | 'screener' | 'trading'

export default function App() {
  const [view, setView] = useState<View>('chart')
  const [symbol, setSymbol] = useState<string>('')
  const [timeframe, setTimeframe] = useState<Timeframe>('1m')
  const [catalog, setCatalog] = useState<CatalogEntry[]>([])
  const [indicators, setIndicators] = useState<IndicatorSpec[]>([])
  const [data, setData] = useState<ApplyResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [panelOpen, setPanelOpen] = useState(false)
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

  const total = data?.candles.length ?? 0

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

  // Clear trade markers when the symbol or timeframe changes.
  useEffect(() => { setTradeMarkers([]) }, [symbol, timeframe])

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
    applyIndicators(symbol, timeframe, indicators)
      .then((res) => !cancelled && setData(res))
      .catch(() => !cancelled && setError('Failed to load chart data'))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [symbol, timeframe, indicators])

  const pickSymbol = (s: string) => {
    setSymbol(s)
    setView('chart')
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <span className="logo">📈</span>
          <span className="brand-name">StockCharts</span>
        </div>
        <nav className="view-nav">
          <button className={view === 'chart' ? 'active' : ''} onClick={() => setView('chart')}>Chart</button>
          <button className={view === 'screener' ? 'active' : ''} onClick={() => setView('screener')}>Screener</button>
          <button className={view === 'trading' ? 'active' : ''} onClick={() => setView('trading')}>Trade</button>
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
          <button
            className={`panel-btn ${panelOpen ? 'active' : ''}`}
            onClick={() => setPanelOpen((o) => !o)}
          >
            Indicators{indicators.length ? ` (${indicators.length})` : ''}
          </button>
        )}
        {view === 'chart' && (
          <button
            className={`panel-btn trade-toggle-btn ${tradeOpen ? 'active' : ''}`}
            onClick={() => setTradeOpen((o) => !o)}
            title="Quick trade panel"
          >
            ⚡ Trade
          </button>
        )}
      </header>

      {view === 'chart' ? (
        <>
          <div className="sub-header">
            <div className="symbol-label">
              {symbol ? (
                <>
                  <strong>{symbol}</strong>
                  <span className="exchange">NSE · {timeframe}</span>
                </>
              ) : (
                <span className="hint">Search and select a stock to begin</span>
              )}
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
            </div>
          </div>

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
      ) : (
        <main className="content">
          <TradingPanel />
        </main>
      )}

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
