import { useEffect, useRef } from 'react'
import { ColorType, createChart, type UTCTimestamp } from 'lightweight-charts'
import { istChartOptions } from '../lib/ist'

interface Props {
  points: { time: number; value: number }[]
  /** Optional second series on its own scale (e.g. drawdown %, daily P&L bars). */
  secondary?: { time: number; value: number }[]
  secondaryKind?: 'drawdown' | 'bars'
  height?: number
}

/** Small equity / P&L curve used by the backtest and portfolio views. */
export default function EquityChart({ points, secondary, secondaryKind = 'drawdown', height = 260 }: Props) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!ref.current || !points.length) return
    const chart = createChart(ref.current, {
      layout: { background: { type: ColorType.Solid, color: '#0e1117' }, textColor: '#8b949e' },
      grid: { vertLines: { color: '#161b22' }, horzLines: { color: '#161b22' } },
      rightPriceScale: { borderColor: '#30363d' },
      localization: istChartOptions.localization,
      timeScale: { borderColor: '#30363d', timeVisible: true, ...istChartOptions.timeScale },
      autoSize: true,
    })
    const up = points[points.length - 1].value >= points[0].value
    const area = chart.addAreaSeries({
      lineColor: up ? '#26a69a' : '#ef5350',
      topColor: up ? 'rgba(38,166,154,0.3)' : 'rgba(239,83,80,0.3)',
      bottomColor: 'rgba(0,0,0,0)',
      lineWidth: 2,
    })
    area.setData(dedupe(points).map((p) => ({ time: p.time as UTCTimestamp, value: p.value })))
    if (secondary?.length) {
      const s = chart.addHistogramSeries({ priceScaleId: 'secondary', priceLineVisible: false })
      s.priceScale().applyOptions({ scaleMargins: { top: secondaryKind === 'drawdown' ? 0.75 : 0.6, bottom: 0 } })
      s.setData(dedupe(secondary).map((p) => ({
        time: p.time as UTCTimestamp,
        value: p.value,
        color: secondaryKind === 'drawdown' || p.value < 0 ? 'rgba(239,83,80,0.55)' : 'rgba(38,166,154,0.55)',
      })))
    }
    chart.timeScale().fitContent()
    return () => chart.remove()
  }, [points, secondary, secondaryKind])

  return <div ref={ref} className="equity-chart" style={{ height }} />
}

/** lightweight-charts needs strictly increasing times; keep the last value per timestamp. */
function dedupe(points: { time: number; value: number }[]) {
  const out: { time: number; value: number }[] = []
  for (const p of points) {
    if (out.length && out[out.length - 1].time === p.time) out[out.length - 1] = p
    else if (!out.length || p.time > out[out.length - 1].time) out.push(p)
  }
  return out
}
