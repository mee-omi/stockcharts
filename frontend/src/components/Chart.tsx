import { useCallback, useEffect, useRef } from 'react'
import {
  createChart,
  ColorType,
  CrosshairMode,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
  type SeriesMarker,
} from 'lightweight-charts'
import type { ApplyResponse, Candle, IndicatorPoint, LiveUpdate, Timeframe, TradeMarker } from '../api/types'
import { subscribeTicks } from '../ws/stomp'

const LINE_COLORS = [
  '#f5a623', '#4caf50', '#e91e63', '#00bcd4', '#9c27b0',
  '#ff5722', '#03a9f4', '#cddc39', '#ff9800', '#8bc34a',
]

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
}

function toSeriesMarkers(
  markers: TradeMarker[],
  cutoff?: number,
): SeriesMarker<UTCTimestamp>[] {
  return markers
    .filter((m) => cutoff === undefined || m.time <= cutoff)
    .map((m) => ({
      time: m.time as UTCTimestamp,
      position: (m.side === 'BUY' ? 'belowBar' : 'aboveBar') as 'belowBar' | 'aboveBar',
      color: m.side === 'BUY' ? '#26a69a' : '#ef5350',
      shape: (m.side === 'BUY' ? 'arrowUp' : 'arrowDown') as 'arrowUp' | 'arrowDown',
      text: `${m.side === 'BUY' ? 'B' : 'S'} ${m.quantity}`,
    }))
    .sort((a, b) => (a.time as number) - (b.time as number))
}

export default function Chart({ symbol, timeframe, data, visibleCount, replayActive, tradeMarkers }: Props) {
  const mainRef = useRef<HTMLDivElement>(null)
  const oscRef = useRef<HTMLDivElement>(null)
  const mainChartRef = useRef<IChartApi | null>(null)
  const oscChartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)
  const lineRefs = useRef<LineRef[]>([])
  const candlesRef = useRef<Candle[]>([])
  const lastBarRef = useRef<Candle | null>(null)
  const tradeMarkersRef = useRef<TradeMarker[]>(tradeMarkers ?? [])

  // Keep the markers ref in sync so renderUpTo always reads fresh markers.
  tradeMarkersRef.current = tradeMarkers ?? []

  const hasOscillators = !!data?.indicators.some((i) => !i.overlay)

  // Reveal the first `n` bars (and the indicator values defined up to that time).
  const renderUpTo = useCallback((n: number) => {
    const candles = candlesRef.current
    const candleSeries = candleSeriesRef.current
    if (!candles.length || !candleSeries) return
    const count = Math.max(1, Math.min(n, candles.length))
    const slice = candles.slice(0, count)
    const cutoff = slice[slice.length - 1].time

    candleSeries.setData(slice.map((c) => ({
      time: c.time as UTCTimestamp, open: c.open, high: c.high, low: c.low, close: c.close,
    })))
    volSeriesRef.current?.setData(slice.map((c) => ({
      time: c.time as UTCTimestamp,
      value: c.volume,
      color: c.close >= c.open ? 'rgba(38,166,154,0.5)' : 'rgba(239,83,80,0.5)',
    })))
    for (const lr of lineRefs.current) {
      lr.series.setData(
        lr.points
          .filter((p) => p.time <= cutoff && p.value !== null)
          .map((p) => ({ time: p.time as UTCTimestamp, value: p.value as number })),
      )
    }
    // Re-apply trade markers, respecting the replay cutoff.
    candleSeries.setMarkers(toSeriesMarkers(tradeMarkersRef.current, cutoff))
    lastBarRef.current = slice[slice.length - 1]
  }, [])

  // Build the chart + series once per dataset.
  useEffect(() => {
    if (!mainRef.current || !data) return

    const main = createChart(mainRef.current, baseChartOptions)
    mainChartRef.current = main
    const candle = main.addCandlestickSeries({
      upColor: '#26a69a', downColor: '#ef5350',
      borderVisible: false, wickUpColor: '#26a69a', wickDownColor: '#ef5350',
    })
    candleSeriesRef.current = candle

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

    lineRefs.current = refs
    candlesRef.current = data.candles
    // Initial fill (the replay effect below refines the view).
    renderUpTo(replayActive && visibleCount ? visibleCount : data.candles.length)

    return () => {
      main.remove()
      osc?.remove()
      mainChartRef.current = null
      oscChartRef.current = null
      candleSeriesRef.current = null
      volSeriesRef.current = null
      lineRefs.current = []
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, hasOscillators])

  // React to replay position changes without rebuilding the chart.
  useEffect(() => {
    if (!data || !candleSeriesRef.current) return
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
  }, [visibleCount, replayActive, data, renderUpTo])

  // Update markers whenever tradeMarkers prop changes (without rebuilding the chart).
  useEffect(() => {
    if (!candleSeriesRef.current) return
    const cutoff = lastBarRef.current?.time
    candleSeriesRef.current.setMarkers(toSeriesMarkers(tradeMarkersRef.current, cutoff))
  }, [tradeMarkers])

  // Live ticks → update the latest bar (suspended during replay).
  useEffect(() => {
    if (!symbol) return
    const unsub = subscribeTicks(symbol, (u: LiveUpdate) => {
      if (replayActive) return
      const candle = candleSeriesRef.current
      const last = lastBarRef.current
      if (!candle || !last) return

      if (timeframe === '1m') {
        const bar: Candle = {
          time: u.time, open: u.open, high: u.high, low: u.low, close: u.close, volume: u.volume,
        }
        if (u.time >= last.time) {
          candle.update({
            time: bar.time as UTCTimestamp,
            open: bar.open, high: bar.high, low: bar.low, close: bar.close,
          })
          volSeriesRef.current?.update({
            time: bar.time as UTCTimestamp,
            value: bar.volume,
            color: bar.close >= bar.open ? 'rgba(38,166,154,0.5)' : 'rgba(239,83,80,0.5)',
          })
          lastBarRef.current = bar
        }
      } else {
        const updated: Candle = {
          ...last, high: Math.max(last.high, u.ltp), low: Math.min(last.low, u.ltp), close: u.ltp,
        }
        candle.update({
          time: updated.time as UTCTimestamp,
          open: updated.open, high: updated.high, low: updated.low, close: updated.close,
        })
        lastBarRef.current = updated
      }
    })
    return unsub
  }, [symbol, timeframe, replayActive])

  if (!data) {
    return <div className="chart-empty">Select a stock to view its chart.</div>
  }

  return (
    <div className="chart-wrap">
      <div ref={mainRef} className="chart-main" />
      {hasOscillators && <div ref={oscRef} className="chart-osc" />}
    </div>
  )
}

