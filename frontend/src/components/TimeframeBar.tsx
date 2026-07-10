import { TIMEFRAMES, type Timeframe } from '../api/types'

interface Props {
  value: Timeframe
  onChange: (tf: Timeframe) => void
}

export default function TimeframeBar({ value, onChange }: Props) {
  return (
    <div className="timeframe-bar">
      {TIMEFRAMES.map((tf) => (
        <button
          key={tf}
          className={`tf-btn ${tf === value ? 'active' : ''}`}
          onClick={() => onChange(tf)}
        >
          {tf}
        </button>
      ))}
    </div>
  )
}
