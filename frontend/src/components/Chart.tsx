import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createChart,
  ColorType,
  CrosshairMode,
  LineStyle,
  PriceScaleMode,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type SeriesType,
  type UTCTimestamp,
  type SeriesMarker,
} from 'lightweight-charts'
import type {
  ApplyResponse,
  Candle,
  ChartType,
  Drawing,
  DrawingTool,
  IndicatorPoint,
  LiveUpdate,
  Timeframe,
  TradeMarker,
} from '../api/types'
import { subscribeTicks } from '../ws/stomp'
import { fibLevels, heikinAshi, LiveBarBuilder, orderedSegment, snapTime } from '../lib/chartMath'

const LINE_COLORS = [
  '#f5a623', '#4caf50', '#e91e63', '#00bcd4', '#9c27b0',
  '#ff5722', '#03a9f4', '#cddc39', '#ff9800', '#8bc34a',
]
const UP = '#26a69a'
const DOWN = '#ef5350'
const DRAW_COLOR = '#58a6ff'
const COMPARE_COLOR = '#bc8cff'

const baseChartOptions = {
  layout: {
    background: { type: ColorType.Solid, color: '#0e1117' },
    textColor: '#8b949e',
  },
  grid: {
    vertLines: { color: '#161b22' },
    horzLines: { color: '#161b22' },
  },
  crosshair: { mode: CrosshairMode.Normal },
  rightPriceScale: { borderColor: '#30363d' },
  timeScale: { borderColor: '#30363d', timeVisible: true, secondsVisible: false },
  autoSize: true,
}

interface LineRef {
  series: ISeriesApi<'Line'>
  points: IndicatorPoint[]
}

interface Props {
  symbol: string
  timeframe: Timeframe
  data: ApplyResponse | null
  /** When replaying, the number of bars (from the start) to reveal. */
  visibleCount?: number
  replayActive?: boolean
  /** Trade markers to render as BUY/SELL arrows on the chart. */
  tradeMarkers?: TradeMarker[]
  chartType?: ChartType
  /** Second symbol overlaid as % change (switches the price scale to percentage mode). */
  compare?: { symbol: string; candles: Candle[] } | null
  drawings?: Drawing[]
  drawingTool?: DrawingTool
  onAddDrawing?: (d: Drawing) => void
  /** Called with a hint while a two-click drawing waits for its second point (null when done). */
  onDrawingHint?: (hint: string | null) => void
}

function toSeriesMarkers(
  markers: TradeMarker[],
  cutoff?: number,
  start?: number,
): SeriesMarker<UTCTimestamp>[] {
  return markers
    // Markers outside the loaded bars would pile up at the chart's edge.
    .filter((m) => (cutoff === undefined || m.time <= cutoff) && (start === undefined || m.time >= start))
    .map((m) => ({
      time: m.time as UTCTimestamp,
      position: (m.side === 'BUY' ? 'belowBar' : 'aboveBar') as 'belowBar' | 'aboveBar',
      color: m.side === 'BUY' ? UP : DOWN,
      shape: (m.side === 'BUY' ? 'arrowUp' : 'arrowDown') as 'arrowUp' | 'arrowDown',
      text: `${m.side === 'BUY' ? 'B' : 'S'} ${m.quantity}`,
    }))
    .sort((a, b) => (a.time as number) - (b.time as number))
}

function isOhlc(t: ChartType): boolean {
  return t === 'candles' || t === 'heikin' || t === 'bars'
}

/** Candles → data for the main series, respecting the chart type. */
function mainData(candles: Candle[], type: ChartType) {
  const src = type === 'heikin' ? heikinAshi(candles) : candles
  if (isOhlc(type)) {
    return src.map((c) => ({ time: c.time as UTCTimestamp, open: c.open, high: c.high, low: c.low, close: c.close }))
  }
  return src.map((c) => ({ time: c.time as UTCTimestamp, value: c.close }))
}

function volumePoint(c: Candle) {
  return {
    time: c.time as UTCTimestamp,
    value: c.volume,
    color: c.close >= c.open ? 'rgba(38,166,154,0.5)' : 'rgba(239,83,80,0.5)',
  }
}

function newId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

export default function Chart({
  symbol, timeframe, data, visibleCount, replayActive, tradeMarkers,
  chartType = 'candles', compare, drawings, drawingTool = 'none', onAddDrawing, onDrawingHint,
}: Props) {
  const mainRef = useRef<HTMLDivElement>(null)
  const oscRef = useRef<HTMLDivElement>(null)
  const mainChartRef = useRef<IChartApi | null>(null)
  const oscChartRef = useRef<IChartApi | null>(null)
  const mainSeriesRef = useRef<ISeriesApi<SeriesType> | null>(null)
  const volSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)
  const lineRefs = useRef<LineRef[]>([])
  const candlesRef = useRef<Candle[]>([])
  const haRef = useRef<Candle[]>([])
  const builderRef = useRef<LiveBarBuilder | null>(null)
  const cutoffRef = useRef<number | undefined>(undefined)
  const tradeMarkersRef = useRef<TradeMarker[]>(tradeMarkers ?? [])
  const toolRef = useRef<DrawingTool>(drawingTool)
  const addDrawingRef = useRef(onAddDrawing)
  const hintRef = useRef(onDrawingHint)
  const pendingPointRef = useRef<{ time: number; price: number } | null>(null)
  // Bumped whenever the chart is rebuilt so overlay effects (compare, drawings) re-attach.
  const [epoch, setEpoch] = useState(0)

  tradeMarkersRef.current = tradeMarkers ?? []
  addDrawingRef.current = onAddDrawing
  hintRef.current = onDrawingHint

  useEffect(() => {
    toolRef.current = drawingTool
    pendingPointRef.current = null
    hintRef.current?.(null)
  }, [drawingTool])

  const hasOscillators = !!data?.indicators.some((i) => !i.overlay)

  // Reveal the first `n` bars (and the indicator values defined up to that time).
  const renderUpTo = useCallback((n: number) => {
    const candles = candlesRef.current
    const series = mainSeriesRef.current
    if (!candles.length || !series) return
    const count = Math.max(1, Math.min(n, candles.length))
    const slice = candles.slice(0, count)
    const cutoff = slice[slice.length - 1].time
    cutoffRef.current = cutoff

    series.setData(mainData(slice, chartType))
    if (chartType === 'heikin') haRef.current = heikinAshi(slice)
    volSeriesRef.current?.setData(slice.map(volumePoint))
    for (const lr of lineRefs.current) {
      lr.series.setData(
        lr.points
          .filter((p) => p.time <= cutoff && p.value !== null)
          .map((p) => ({ time: p.time as UTCTimestamp, value: p.value as number })),
      )
    }
    series.setMarkers(toSeriesMarkers(tradeMarkersRef.current, cutoff, candles[0].time))
    builderRef.current = new LiveBarBuilder(slice[slice.length - 1], timeframe)
  }, [chartType, timeframe])

  // Build the chart + series once per dataset / chart type.
  useEffect(() => {
    if (!mainRef.current || !data) return

    const main = createChart(mainRef.current, baseChartOptions)
    mainChartRef.current = main
    let series: ISeriesApi<SeriesType>
    if (chartType === 'bars') {
      series = main.addBarSeries({ upColor: UP, downColor: DOWN })
    } else if (chartType === 'line') {
      series = main.addLineSeries({ color: '#e6edf3', lineWidth: 2 })
    } else if (chartType === 'area') {
      series = main.addAreaSeries({
        lineColor: '#58a6ff', topColor: 'rgba(88,166,255,0.35)', bottomColor: 'rgba(88,166,255,0.02)', lineWidth: 2,
      })
    } else {
      series = main.addCandlestickSeries({
        upColor: UP, downColor: DOWN, borderVisible: false, wickUpColor: UP, wickDownColor: DOWN,
      })
    }
    mainSeriesRef.current = series

    const volume = main.addHistogramSeries({ priceFormat: { type: 'volume' }, priceScaleId: '' })
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } })
    volSeriesRef.current = volume

    const refs: LineRef[] = []
    let colorIdx = 0
    data.indicators.filter((i) => i.overlay).forEach((ind) =>
      ind.lines.forEach((line) => {
        const s = main.addLineSeries({
          color: LINE_COLORS[colorIdx++ % LINE_COLORS.length],
          lineWidth: 2, priceLineVisible: false, lastValueVisible: false,
        })
        refs.push({ series: s, points: line.points })
      }),
    )

    let osc: IChartApi | null = null
    if (oscRef.current && hasOscillators) {
      osc = createChart(oscRef.current, baseChartOptions)
      oscChartRef.current = osc
      let oc = 0
      data.indicators.filter((i) => !i.overlay).forEach((ind) =>
        ind.lines.forEach((line) => {
          const s = osc!.addLineSeries({
            color: LINE_COLORS[oc++ % LINE_COLORS.length],
            lineWidth: 2, title: `${ind.type}:${line.name}`,
          })
          refs.push({ series: s, points: line.points })
        }),
      )
      const sync = (src: IChartApi, dst: IChartApi) =>
        src.timeScale().subscribeVisibleLogicalRangeChange((r) => {
          if (r) dst.timeScale().setVisibleLogicalRange(r)
        })
      sync(main, osc)
      sync(osc, main)
    }

    // Drawing tools: one or two clicks on the price pane. Plain DOM clicks are used because
    // lightweight-charts folds a second click within ~500ms into a double-click, even when it
    // lands far from the first, which would swallow quick two-point drawings.
    const container = mainRef.current
    const onDomClick = (e: MouseEvent) => {
      const tool = toolRef.current
      if (tool === 'none') return
      const rect = container.getBoundingClientRect()
      const x = e.clientX - rect.left
      const y = e.clientY - rect.top
      // Ignore clicks on the price axis (right) and the time axis (bottom).
      if (x > main.timeScale().width() || y > rect.height - main.timeScale().height()) return
      const price = series.coordinateToPrice(y)
      if (price === null) return
      if (tool === 'hline') {
        addDrawingRef.current?.({ id: newId(), type: 'hline', price })
        return
      }
      const time = main.timeScale().coordinateToTime(x) as number | null
      if (time === null) return
      const first = pendingPointRef.current
      if (!first) {
        pendingPointRef.current = { time, price }
        hintRef.current?.('Click the second point')
        return
      }
      pendingPointRef.current = null
      hintRef.current?.(null)
      if (first.time === time) return
      addDrawingRef.current?.({ id: newId(), type: tool, t1: first.time, p1: first.price, t2: time, p2: price })
    }
    container.addEventListener('click', onDomClick)

    lineRefs.current = refs
    candlesRef.current = [...data.candles]
    // Initial fill (the replay effect below refines the view).
    renderUpTo(replayActive && visibleCount ? visibleCount : data.candles.length)
    setEpoch((e) => e + 1)

    return () => {
      container.removeEventListener('click', onDomClick)
      main.remove()
      osc?.remove()
      mainChartRef.current = null
      oscChartRef.current = null
      mainSeriesRef.current = null
      volSeriesRef.current = null
      lineRefs.current = []
      builderRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, hasOscillators, chartType])

  // React to replay position changes without rebuilding the chart.
  useEffect(() => {
    if (!data || !mainSeriesRef.current) return
    const total = candlesRef.current.length
    const n = replayActive && visibleCount ? visibleCount : total
    renderUpTo(n)
    if (replayActive) {
      // Keep the current bar near the right edge with a trailing window.
      mainChartRef.current?.timeScale().setVisibleLogicalRange({ from: Math.max(0, n - 130), to: n + 3 })
    } else {
      mainChartRef.current?.timeScale().fitContent()
      oscChartRef.current?.timeScale().fitContent()
    }
  }, [visibleCount, replayActive, data, renderUpTo, epoch])

  // Update markers whenever tradeMarkers prop changes (without rebuilding the chart).
  useEffect(() => {
    mainSeriesRef.current?.setMarkers(
      toSeriesMarkers(tradeMarkersRef.current, cutoffRef.current, candlesRef.current[0]?.time))
  }, [tradeMarkers, epoch])

  // Compare overlay: both series in percentage mode so different price levels line up.
  useEffect(() => {
    const main = mainChartRef.current
    if (!main || !compare || !compare.candles.length) return
    const cutoff = cutoffRef.current
    const line = main.addLineSeries({
      color: COMPARE_COLOR, lineWidth: 2, priceLineVisible: false, title: compare.symbol,
    })
    line.setData(compare.candles
      .filter((c) => cutoff === undefined || c.time <= cutoff)
      .map((c) => ({ time: c.time as UTCTimestamp, value: c.close })))
    main.priceScale('right').applyOptions({ mode: PriceScaleMode.Percentage })
    return () => {
      if (mainChartRef.current !== main) return // chart already torn down
      main.removeSeries(line)
      main.priceScale('right').applyOptions({ mode: PriceScaleMode.Normal })
    }
  }, [compare, epoch, visibleCount, replayActive])

  // Drawings: horizontal lines and fib levels as price lines, trend lines as 2-point series.
  useEffect(() => {
    const main = mainChartRef.current
    const series = mainSeriesRef.current
    if (!main || !series || !drawings?.length) return
    const priceLines: IPriceLine[] = []
    const trendSeries: ISeriesApi<'Line'>[] = []
    for (const d of drawings) {
      if (d.type === 'hline') {
        priceLines.push(series.createPriceLine({
          price: d.price, color: DRAW_COLOR, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: true,
          title: '',
        }))
      } else if (d.type === 'fib') {
        for (const lvl of fibLevels(d.p1, d.p2)) {
          priceLines.push(series.createPriceLine({
            price: lvl.price, color: 'rgba(245,166,35,0.7)', lineWidth: 1, lineStyle: LineStyle.Dotted,
            axisLabelVisible: true, title: `${(lvl.ratio * 100).toFixed(1)}%`,
          }))
        }
      } else {
        const times = candlesRef.current.map((c) => c.time)
        const t1 = snapTime(times, d.t1)
        const t2 = snapTime(times, d.t2)
        const seg = t1 === null || t2 === null ? null : orderedSegment(t1, d.p1, t2, d.p2)
        if (!seg) continue
        const s = main.addLineSeries({
          color: DRAW_COLOR, lineWidth: 2, priceLineVisible: false, lastValueVisible: false,
          crosshairMarkerVisible: false,
        })
        s.setData(seg.map((p) => ({ time: p.time as UTCTimestamp, value: p.value })))
        trendSeries.push(s)
      }
    }
    return () => {
      if (mainChartRef.current !== main) return
      priceLines.forEach((l) => series.removePriceLine(l))
      trendSeries.forEach((s) => main.removeSeries(s))
    }
  }, [drawings, epoch])

  // Live ticks → update (or roll) the latest bar at the chart's timeframe; paused during replay.
  useEffect(() => {
    if (!symbol) return
    const unsub = subscribeTicks(symbol, (u: LiveUpdate) => {
      if (replayActive) return
      const series = mainSeriesRef.current
      const builder = builderRef.current
      if (!series || !builder) return
      const res = builder.apply(u)
      if (!res) return
      const { bar, isNew } = res
      const candles = candlesRef.current
      if (isNew) candles.push(bar)
      else candles[candles.length - 1] = bar

      if (chartType === 'heikin') {
        const ha = haRef.current
        const prev = isNew ? ha[ha.length - 1] : ha[ha.length - 2]
        const close = (bar.open + bar.high + bar.low + bar.close) / 4
        const open = prev ? (prev.open + prev.close) / 2 : (bar.open + bar.close) / 2
        const haBar = { ...bar, open, close, high: Math.max(bar.high, open, close), low: Math.min(bar.low, open, close) }
        if (isNew) ha.push(haBar)
        else ha[ha.length - 1] = haBar
        series.update({ time: bar.time as UTCTimestamp, open, high: haBar.high, low: haBar.low, close })
      } else if (isOhlc(chartType)) {
        series.update({ time: bar.time as UTCTimestamp, open: bar.open, high: bar.high, low: bar.low, close: bar.close })
      } else {
        series.update({ time: bar.time as UTCTimestamp, value: bar.close })
      }
      volSeriesRef.current?.update(volumePoint(bar))
    })
    return unsub
  }, [symbol, timeframe, replayActive, chartType, epoch])

  if (!data) {
    return <div className="chart-empty">Select a stock to view its chart.</div>
  }

  return (
    <div className={`chart-wrap${drawingTool !== 'none' ? ' drawing' : ''}`}>
      <div ref={mainRef} className="chart-main" data-testid="chart-main" />
      {hasOscillators && <div ref={oscRef} className="chart-osc" />}
    </div>
  )
}
