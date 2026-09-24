import { describe, expect, it } from 'vitest'
import type { Candle, LiveUpdate } from '../api/types'
import { fibLevels, heikinAshi, LiveBarBuilder, olderRangeStart, orderedSegment, snapTime } from './chartMath'

const c = (time: number, open: number, high: number, low: number, close: number, volume = 100): Candle =>
  ({ time, open, high, low, close, volume })

const u = (time: number, ltp: number, volume: number, extra: Partial<LiveUpdate> = {}): LiveUpdate => ({
  symbol: 'X', time, open: ltp, high: ltp, low: ltp, close: ltp, volume, ltp, ...extra,
})

describe('heikinAshi', () => {
  it('smooths candles with the standard recurrence', () => {
    const ha = heikinAshi([c(0, 10, 12, 9, 11), c(60, 11, 13, 10, 12)])
    expect(ha[0].close).toBeCloseTo((10 + 12 + 9 + 11) / 4)
    expect(ha[0].open).toBeCloseTo((10 + 11) / 2)
    expect(ha[1].open).toBeCloseTo((ha[0].open + ha[0].close) / 2)
    expect(ha[1].high).toBeGreaterThanOrEqual(Math.max(ha[1].open, ha[1].close))
    expect(ha[1].low).toBeLessThanOrEqual(Math.min(ha[1].open, ha[1].close))
    expect(ha.map((x) => x.time)).toEqual([0, 60])
  })
})

describe('LiveBarBuilder', () => {
  it('updates the current 5m bar and rolls to a new one at the bucket boundary', () => {
    const b = new LiveBarBuilder(c(300, 100, 101, 99, 100, 1000), '5m', 0)
    let r = b.apply(u(360, 102, 50, { high: 102.5 }))!
    expect(r.isNew).toBe(false)
    expect(r.bar).toMatchObject({ time: 300, open: 100, high: 102.5, low: 99, close: 102 })
    expect(r.bar.volume).toBe(1050) // history volume + the new minute's volume

    r = b.apply(u(360, 103, 80))! // same minute, running volume grows
    expect(r.bar.volume).toBe(1080)

    r = b.apply(u(600, 104, 10))! // 600 = next 5m bucket
    expect(r.isNew).toBe(true)
    expect(r.bar).toMatchObject({ time: 600, open: 104, close: 104, volume: 10 })
  })

  it('does not double count a minute already in progress when history loaded', () => {
    const b = new LiveBarBuilder(c(0, 100, 100, 100, 100, 500), '5m', 125) // loaded during minute 120
    expect(b.apply(u(120, 101, 40))!.bar.volume).toBe(500)
    expect(b.apply(u(120, 101, 55))!.bar.volume).toBe(515)
  })

  it('ignores stale updates', () => {
    const b = new LiveBarBuilder(c(600, 1, 1, 1, 1), '5m', 0)
    expect(b.apply(u(240, 2, 1))).toBeNull()
  })
})

describe('drawing helpers', () => {
  it('computes fib retracements from swing start to swing end', () => {
    const lv = fibLevels(100, 200)
    expect(lv[0]).toEqual({ ratio: 0, price: 200 })
    expect(lv.find((l) => l.ratio === 0.5)!.price).toBe(150)
    expect(lv[lv.length - 1]).toEqual({ ratio: 1, price: 100 })
  })

  it('orders trend-line points by time and rejects vertical lines', () => {
    expect(orderedSegment(200, 5, 100, 4)).toEqual([{ time: 100, value: 4 }, { time: 200, value: 5 }])
    expect(orderedSegment(100, 1, 100, 2)).toBeNull()
  })

  it('snaps to the nearest bar time and clamps outside the range', () => {
    const times = [0, 60, 120, 180]
    expect(snapTime(times, 70)).toBe(60)
    expect(snapTime(times, 100)).toBe(120)
    expect(snapTime(times, -50)).toBe(0)
    expect(snapTime(times, 999)).toBe(180)
    expect(snapTime([], 5)).toBeNull()
  })

  it('computes the start of an older history range in ms', () => {
    expect(olderRangeStart(86_400 * 10, '1d', 5)).toBe(86_400 * 5 * 1000)
  })
})
