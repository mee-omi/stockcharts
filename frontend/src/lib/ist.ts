/**
 * Indian market time. Chart data stays in epoch seconds (UTC); only labels are rendered in IST,
 * and live bars are bucketed like the NSE feed: intraday bars anchored at the 09:15 session
 * open, daily bars at IST midnight.
 */
import { TickMarkType, type Time } from 'lightweight-charts'
import type { Timeframe } from '../api/types'

export const IST_TZ = 'Asia/Kolkata'
export const IST_OFFSET_SEC = 5.5 * 3600
const DAY = 86_400
export const SESSION_OPEN_SEC = 9 * 3600 + 15 * 60 // 09:15 IST, seconds into the IST day
export const SESSION_CLOSE_SEC = 15 * 3600 + 30 * 60 // 15:30 IST
/** Bars in one full session per timeframe (the last bar of 1h / 30m slots is cut short at 15:30). */
const SESSION_BARS: Record<Timeframe, number> = { '1m': 375, '5m': 75, '15m': 25, '30m': 13, '1h': 7, '1d': 1 }

const TF_SECONDS: Record<Timeframe, number> = {
  '1m': 60, '5m': 300, '15m': 900, '30m': 1800, '1h': 3600, '1d': DAY,
}

/** Seconds since IST midnight for an epoch-seconds timestamp. */
export function istSecondOfDay(t: number): number {
  return (((t + IST_OFFSET_SEC) % DAY) + DAY) % DAY
}

/** Epoch seconds of the start of the bar containing `t` (NSE convention). */
export function barStart(tf: Timeframe, t: number): number {
  const sod = istSecondOfDay(t)
  if (tf === '1d') return t - sod
  const step = TF_SECONDS[tf]
  if (sod < SESSION_OPEN_SEC) return t - (((t % step) + step) % step)
  return t - ((sod - SESSION_OPEN_SEC) % step)
}

/**
 * Epoch ms far enough before `earliestSec` to hold roughly `bars` more session bars:
 * whole sessions for the bars, padded for nights, weekends and a few holidays.
 */
export function sessionSpanBefore(earliestSec: number, tf: Timeframe, bars: number): number {
  const sessions = Math.ceil(bars / SESSION_BARS[tf])
  const calendarDays = Math.ceil(sessions * 7 / 5) + 3
  return (earliestSec - calendarDays * DAY) * 1000
}

const dateFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, day: 'numeric', month: 'short', year: '2-digit' })
const dayMonthFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, day: 'numeric', month: 'short' })
const monthFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, month: 'short' })
const yearFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, year: 'numeric' })
const hmFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const weekdayFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, weekday: 'short' })

function toSec(time: Time): number {
  if (typeof time === 'number') return time
  if (typeof time === 'string') return Date.parse(time) / 1000
  return Date.UTC(time.year, time.month - 1, time.day) / 1000 - IST_OFFSET_SEC
}

/** Crosshair / tooltip label: "Thu 24 Sep 26, 14:05 IST", date only for daily bars. */
export function istCrosshairLabel(time: Time): string {
  const t = toSec(time)
  const d = new Date(t * 1000)
  const date = `${weekdayFmt.format(d)} ${dateFmt.format(d)}`
  return istSecondOfDay(t) === 0 ? date : `${date}, ${hmFmt.format(d)} IST`
}

/** Time-axis tick labels in IST. */
export function istTickMark(time: Time, type: TickMarkType): string {
  const d = new Date(toSec(time) * 1000)
  switch (type) {
    case TickMarkType.Year: return yearFmt.format(d)
    case TickMarkType.Month: return monthFmt.format(d)
    case TickMarkType.DayOfMonth: return dayMonthFmt.format(d)
    default: return hmFmt.format(d)
  }
}

/** Chart options that render every time label in IST. */
export const istChartOptions = {
  localization: { timeFormatter: istCrosshairLabel },
  timeScale: { tickMarkFormatter: istTickMark },
}

