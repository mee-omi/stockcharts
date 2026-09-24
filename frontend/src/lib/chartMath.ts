import type { Candle, LiveUpdate, Timeframe } from '../api/types'
import { barStart, sessionSpanBefore } from './ist'

const TF_SECONDS: Record<Timeframe, number> = {
  '1m': 60,
  '5m': 300,
  '15m': 900,
  '30m': 1800,
  '1h': 3600,
  '1d': 86400,
}

export function timeframeSeconds(tf: Timeframe): number {
  return TF_SECONDS[tf]
}

/** Heikin-Ashi transform (same timestamps, smoothed OHLC). */
export function heikinAshi(candles: Candle[]): Candle[] {
  const out: Candle[] = []
  let prevOpen = 0
  let prevClose = 0
  candles.forEach((c, i) => {
    const close = (c.open + c.high + c.low + c.close) / 4
    const open = i === 0 ? (c.open + c.close) / 2 : (prevOpen + prevClose) / 2
    out.push({
      time: c.time,
      open,
      close,
      high: Math.max(c.high, open, close),
      low: Math.min(c.low, open, close),
      volume: c.volume,
    })
    prevOpen = open
    prevClose = close
  })
  return out
}

/**
 * Folds the backend's live 1-minute updates into the chart's current bar at any timeframe,
 * starting a new bar when an update crosses into the next bucket. Volume is tracked per minute
 * (updates carry the minute's running volume), counting only what arrived after the chart loaded
 * on top of the historical bar's volume.
 */
export class LiveBarBuilder {
  private bar: Candle
  private readonly tf: Timeframe
  private base: number
  private readonly loadedMinute: number
  private first = new Map<number, number>()
  private latest = new Map<number, number>()

  constructor(last: Candle, tf: Timeframe, loadedAtSec: number = Math.floor(Date.now() / 1000)) {
    this.bar = { ...last }
    this.tf = tf
    this.base = last.volume
    this.loadedMinute = loadedAtSec - (loadedAtSec % 60)
  }

  current(): Candle {
    return this.bar
  }

  /** Returns the updated bar and whether it is a new one, or null for a stale update. */
  apply(u: LiveUpdate): { bar: Candle; isNew: boolean } | null {
    const bucket = barStart(this.tf, u.time)
    let isNew = false
    if (bucket < this.bar.time) return null
    if (bucket > this.bar.time) {
      this.bar = { time: bucket, open: u.open, high: u.high, low: u.low, close: u.close, volume: 0 }
      this.base = 0
      this.first.clear()
      this.latest.clear()
      isNew = true
    } else {
      this.bar = {
        ...this.bar,
        high: Math.max(this.bar.high, u.high),
        low: Math.min(this.bar.low, u.low),
        close: u.close,
      }
    }
    if (!this.first.has(u.time)) {
      // A minute already in progress when history loaded is partly counted in `base`.
      this.first.set(u.time, !isNew && u.time <= this.loadedMinute ? u.volume : 0)
    }
    this.latest.set(u.time, u.volume)
    let added = 0
    this.latest.forEach((v, minute) => { added += v - (this.first.get(minute) ?? 0) })
    this.bar = { ...this.bar, volume: this.base + added }
    return { bar: this.bar, isNew }
  }
}

export const FIB_RATIOS = [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1] as const

/** Retracement levels of a swing from p1 to p2: ratio 0 sits at p2, ratio 1 back at p1. */
export function fibLevels(p1: number, p2: number): { ratio: number; price: number }[] {
  return FIB_RATIOS.map((ratio) => ({ ratio, price: p2 - (p2 - p1) * ratio }))
}

/** Two chart points ordered by time; null when both share a timestamp (not drawable). */
export function orderedSegment(
  t1: number, p1: number, t2: number, p2: number,
): [{ time: number; value: number }, { time: number; value: number }] | null {
  if (t1 === t2) return null
  return t1 < t2
    ? [{ time: t1, value: p1 }, { time: t2, value: p2 }]
    : [{ time: t2, value: p2 }, { time: t1, value: p1 }]
}

/**
 * Start of a range reaching about `bars` more bars back than `earliestSec`, in epoch ms. Bars
 * exist only in NSE sessions, so the span covers whole sessions plus nights and weekends.
 */
export function olderRangeStart(earliestSec: number, tf: Timeframe, bars: number): number {
  return sessionSpanBefore(earliestSec, tf, bars)
}

/**
 * Snap a timestamp to the nearest bar time (times ascending). Drawing anchors must sit on
 * existing bars: a series point at any other time would insert a gap into the time axis.
 */
export function snapTime(times: number[], t: number): number | null {
  if (!times.length) return null
  let lo = 0
  let hi = times.length - 1
  if (t <= times[lo]) return times[lo]
  if (t >= times[hi]) return times[hi]
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1
    if (times[mid] <= t) lo = mid
    else hi = mid
  }
  return t - times[lo] <= times[hi] - t ? times[lo] : times[hi]
}
