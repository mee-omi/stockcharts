import { describe, expect, it } from 'vitest'
import { TickMarkType } from 'lightweight-charts'
import { barStart, istCrosshairLabel, istTickMark } from './ist'
import { LiveBarBuilder } from './chartMath'

/** Epoch seconds of an IST wall-clock time. */
const ist = (s: string) => Date.parse(`${s}+05:30`) / 1000

describe('NSE bar buckets', () => {
  it('anchors intraday bars at the 09:15 open', () => {
    expect(barStart('1h', ist('2026-09-24T11:14:00'))).toBe(ist('2026-09-24T10:15:00'))
    expect(barStart('1h', ist('2026-09-24T15:29:00'))).toBe(ist('2026-09-24T15:15:00'))
    expect(barStart('30m', ist('2026-09-24T10:00:00'))).toBe(ist('2026-09-24T09:45:00'))
    expect(barStart('15m', ist('2026-09-24T09:31:00'))).toBe(ist('2026-09-24T09:30:00'))
    expect(barStart('1m', ist('2026-09-24T09:31:40'))).toBe(ist('2026-09-24T09:31:00'))
  })

  it('starts daily bars at IST midnight', () => {
    expect(barStart('1d', ist('2026-09-24T14:00:00'))).toBe(ist('2026-09-24T00:00:00'))
    expect(barStart('1d', ist('2026-09-24T00:00:00'))).toBe(ist('2026-09-24T00:00:00'))
  })

  it('rolls a live 1h bar at 10:15, not at 10:00', () => {
    const b = new LiveBarBuilder({ time: ist('2026-09-24T09:15:00'), open: 100, high: 100, low: 100, close: 100, volume: 0 }, '1h', 0)
    const up = (t: string, px: number) => ({ symbol: 'X', time: ist(t), open: px, high: px, low: px, close: px, volume: 1, ltp: px })
    expect(b.apply(up('2026-09-24T10:05:00', 101))!.isNew).toBe(false)
    const r = b.apply(up('2026-09-24T10:15:00', 102))!
    expect(r.isNew).toBe(true)
    expect(r.bar.time).toBe(ist('2026-09-24T10:15:00'))
  })
})

describe('IST labels', () => {
  it('shows intraday times in IST and daily bars as dates', () => {
    expect(istCrosshairLabel(ist('2026-09-24T14:05:00') as never)).toMatch(/24 Sept? 26, 14:05 IST$/)
    expect(istCrosshairLabel(ist('2026-09-24T00:00:00') as never)).toMatch(/^Thu 24 Sept? 26$/)
    expect(istTickMark(ist('2026-09-24T09:15:00') as never, TickMarkType.Time)).toBe('09:15')
    // 18:30 UTC is already the next day in India.
    expect(istTickMark(Date.parse('2026-09-23T18:30:00Z') / 1000 as never, TickMarkType.DayOfMonth)).toMatch(/^24 Sept?$/)
  })
})

describe('market chip label', () => {
  it('says when the session opens or closes, in IST', async () => {
    const { marketLabel } = await import('../components/MarketStatusChip')
    const ms = (s: string) => Date.parse(`${s}+05:30`)
    expect(marketLabel({ open: true, now: ms('2026-09-24T11:00:00'), nextChange: ms('2026-09-24T15:30:00'), timezone: 'Asia/Kolkata' }))
      .toBe('NSE open · closes 15:30')
    expect(marketLabel({ open: false, now: ms('2026-09-24T08:00:00'), nextChange: ms('2026-09-24T09:15:00'), timezone: 'Asia/Kolkata' }))
      .toBe('NSE closed · opens 09:15')
    expect(marketLabel({ open: false, now: ms('2026-09-25T18:00:00'), nextChange: ms('2026-09-28T09:15:00'), timezone: 'Asia/Kolkata' }))
      .toBe('NSE closed · opens Mon 09:15')
  })
})
