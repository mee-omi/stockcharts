import { useMemo } from 'react'
import type { CatalogEntry, IndicatorSpec } from '../api/types'

interface Props {
  catalog: CatalogEntry[]
  selected: IndicatorSpec[]
  onChange: (specs: IndicatorSpec[]) => void
}

function defaultParams(entry: CatalogEntry): Record<string, number> {
  const p: Record<string, number> = {}
  entry.params.forEach((d) => (p[d.name] = d.defaultValue))
  return p
}

export default function IndicatorPanel({ catalog, selected, onChange }: Props) {
  const selectedByType = useMemo(() => {
    const m = new Map<string, IndicatorSpec>()
    selected.forEach((s) => m.set(s.type, s))
    return m
  }, [selected])

  const toggle = (entry: CatalogEntry) => {
    if (selectedByType.has(entry.code)) {
      onChange(selected.filter((s) => s.type !== entry.code))
    } else {
      onChange([...selected, { type: entry.code, params: defaultParams(entry) }])
    }
  }

  const setParam = (type: string, name: string, value: number) => {
    onChange(
      selected.map((s) =>
        s.type === type ? { ...s, params: { ...s.params, [name]: value } } : s,
      ),
    )
  }

  return (
    <div className="indicator-panel">
      <div className="panel-title">Indicators</div>
      <div className="indicator-list">
        {catalog.map((entry) => {
          const active = selectedByType.get(entry.code)
          return (
            <div key={entry.code} className={`indicator-item ${active ? 'on' : ''}`}>
              <label className="indicator-toggle">
                <input
                  type="checkbox"
                  checked={!!active}
                  onChange={() => toggle(entry)}
                />
                <span title={entry.label}>{entry.code}</span>
                {entry.custom && <span className="badge custom">custom</span>}
                <span className={`badge ${entry.overlay ? 'overlay' : 'pane'}`}>
                  {entry.overlay ? 'overlay' : 'pane'}
                </span>
              </label>
              {active && entry.params.length > 0 && (
                <div className="indicator-params">
                  {entry.params.map((d) => (
                    <label key={d.name} className="param">
                      {d.name}
                      <input
                        type="number"
                        min={d.min}
                        max={d.max}
                        value={active.params[d.name] ?? d.defaultValue}
                        onChange={(e) =>
                          setParam(entry.code, d.name, Number(e.target.value))
                        }
                      />
                    </label>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
