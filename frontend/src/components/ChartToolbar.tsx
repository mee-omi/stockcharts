import { CHART_TYPES, type ChartType, type DrawingTool } from '../api/types'
import StockSearch from './StockSearch'

const TYPE_LABELS: Record<ChartType, string> = {
  candles: 'Candles',
  heikin: 'Heikin-Ashi',
  bars: 'OHLC bars',
  line: 'Line',
  area: 'Area',
}

const TOOLS: { tool: DrawingTool; label: string; title: string }[] = [
  { tool: 'hline', label: '―', title: 'Horizontal line (1 click)' },
  { tool: 'trend', label: '╱', title: 'Trend line (2 clicks)' },
  { tool: 'fib', label: 'Fib', title: 'Fibonacci retracement (2 clicks: swing start, swing end)' },
]

interface Props {
  chartType: ChartType
  onChartType: (t: ChartType) => void
  compareSymbol: string | null
  onCompare: (symbol: string | null) => void
  tool: DrawingTool
  onTool: (t: DrawingTool) => void
  drawingCount: number
  onUndoDrawing: () => void
  onClearDrawings: () => void
  hint: string | null
  onLoadMore: () => void
  loadingMore: boolean
  disabled: boolean
}

export default function ChartToolbar({
  chartType, onChartType, compareSymbol, onCompare, tool, onTool, drawingCount, onUndoDrawing,
  onClearDrawings, hint, onLoadMore, loadingMore, disabled,
}: Props) {
  return (
    <div className="chart-toolbar" role="toolbar" aria-label="Chart tools">
      <select
        className="tb-select"
        value={chartType}
        onChange={(e) => onChartType(e.target.value as ChartType)}
        aria-label="Chart type"
      >
        {CHART_TYPES.map((t) => <option key={t} value={t}>{TYPE_LABELS[t]}</option>)}
      </select>

      <div className="tb-group">
        {TOOLS.map((t) => (
          <button
            key={t.tool}
            className={`tb-btn ${tool === t.tool ? 'active' : ''}`}
            title={t.title}
            aria-label={t.title}
            aria-pressed={tool === t.tool}
            disabled={disabled}
            onClick={() => onTool(tool === t.tool ? 'none' : t.tool)}
          >
            {t.label}
          </button>
        ))}
        <button className="tb-btn" title="Undo last drawing" aria-label="Undo last drawing"
                disabled={disabled || !drawingCount} onClick={onUndoDrawing}>↶</button>
        <button className="tb-btn" title="Clear drawings" aria-label="Clear drawings"
                disabled={disabled || !drawingCount} onClick={onClearDrawings}>✕</button>
      </div>

      <div className="tb-compare">
        {compareSymbol ? (
          <span className="compare-chip">
            vs <strong>{compareSymbol}</strong>
            <button onClick={() => onCompare(null)} aria-label="Remove comparison">✕</button>
          </span>
        ) : (
          <StockSearch
            compact
            clearOnSelect
            placeholder="Compare with…"
            ariaLabel="Compare with symbol"
            onSelect={(s) => onCompare(s)}
          />
        )}
      </div>

      <button className="tb-btn wide" onClick={onLoadMore} disabled={disabled || loadingMore}
              title="Load older history">
        {loadingMore ? 'Loading…' : '⟵ More history'}
      </button>

      {hint && <span className="tb-hint">{hint}</span>}
      {!hint && tool !== 'none' && (
        <span className="tb-hint">{tool === 'hline' ? 'Click a price level' : 'Click the first point'}</span>
      )}
    </div>
  )
}
