import type { UTCTimestamp } from 'lightweight-charts'
import { istCrosshairLabel } from '../lib/ist'

interface Props {
  total: number
  count: number
  playing: boolean
  speed: number
  currentTime?: number // epoch seconds of the current (last revealed) bar
  onCount: (n: number) => void
  onPlayToggle: () => void
  onStep: (dir: number) => void
  onSpeed: (s: number) => void
  onExit: () => void
}

const SPEEDS = [1, 2, 5, 10] // bars per second

function fmt(epochSec?: number): string {
  return epochSec ? istCrosshairLabel(epochSec as UTCTimestamp) : '—'
}

export default function ReplayControls({
  total, count, playing, speed, currentTime,
  onCount, onPlayToggle, onStep, onSpeed, onExit,
}: Props) {
  const atEnd = count >= total
  return (
    <div className="replay-bar">
      <button className="replay-btn exit" onClick={onExit} title="Exit replay">✕</button>
      <button className="replay-btn" onClick={() => onStep(-1)} disabled={count <= 1} title="Step back">⏮</button>
      <button className="replay-btn play" onClick={onPlayToggle} disabled={atEnd} title={playing ? 'Pause' : 'Play'}>
        {playing ? '⏸' : '▶'}
      </button>
      <button className="replay-btn" onClick={() => onStep(1)} disabled={atEnd} title="Step forward">⏭</button>

      <select className="replay-speed" value={speed} onChange={(e) => onSpeed(Number(e.target.value))}>
        {SPEEDS.map((s) => <option key={s} value={s}>{s}×</option>)}
      </select>

      <input
        className="replay-slider"
        type="range"
        min={1}
        max={Math.max(1, total)}
        value={count}
        onChange={(e) => onCount(Number(e.target.value))}
      />

      <div className="replay-readout">
        <span className="replay-pos">{count} / {total}</span>
        <span className="replay-date">{fmt(currentTime)}</span>
      </div>
    </div>
  )
}
