import { useEffect, useState } from 'react'
import { getMarketStatus } from '../api/client'
import type { MarketStatus } from '../api/types'
import { IST_TZ } from '../lib/ist'

const timeFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const dayFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, weekday: 'short' })
const dateFmt = new Intl.DateTimeFormat('en-IN', { timeZone: IST_TZ, day: 'numeric', month: 'short' })

/** "NSE open · closes 15:30" / "NSE closed · opens Mon 09:15" (IST, holidays from the backend). */
export function marketLabel(s: MarketStatus): string {
  const at = new Date(s.nextChange)
  const time = timeFmt.format(at)
  if (s.open) return `NSE open · closes ${time}`
  const days = (s.nextChange - s.now) / 86_400_000
  const when = days < 1 && dayFmt.format(at) === dayFmt.format(new Date(s.now)) ? time
    : days < 6 ? `${dayFmt.format(at)} ${time}` : `${dateFmt.format(at)} ${time}`
  return `NSE closed · opens ${when}`
}

export default function MarketStatusChip() {
  const [status, setStatus] = useState<MarketStatus | null>(null)

  useEffect(() => {
    let timer: number | undefined
    const load = () => {
      getMarketStatus().then((s) => {
        setStatus(s)
        // Refresh at the next open/close (and at least every 5 minutes).
        const wait = Math.min(Math.max(s.nextChange - s.now + 1000, 5000), 300_000)
        timer = window.setTimeout(load, wait)
      }).catch(() => { timer = window.setTimeout(load, 60_000) })
    }
    load()
    return () => window.clearTimeout(timer)
  }, [])

  if (!status) return null
  return (
    <span className={`market-chip ${status.open ? 'open' : 'closed'}`} title="NSE equity session, 09:15–15:30 IST">
      <span className="market-dot" />
      {marketLabel(status)}
    </span>
  )
}
