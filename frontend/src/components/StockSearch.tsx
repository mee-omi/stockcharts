import AsyncSelect from 'react-select/async'
import type { Instrument } from '../api/types'
import { searchInstruments } from '../api/client'

interface Option {
  value: string
  label: string
  instrument: Instrument
}

function toOption(i: Instrument): Option {
  const tag = i.instrumentType === 'INDEX' ? ' · index' : i.exchange !== 'NSE' ? ` · ${i.exchange}` : ''
  return { value: i.symbol, label: `${i.symbol} — ${i.name}${tag}`, instrument: i }
}

interface Props {
  onSelect: (symbol: string) => void
  placeholder?: string
  compact?: boolean
  /** Clear the box after a pick (for "add symbol" style inputs). */
  clearOnSelect?: boolean
  ariaLabel?: string
}

export default function StockSearch({ onSelect, placeholder, compact, clearOnSelect, ariaLabel }: Props) {
  const loadOptions = async (input: string): Promise<Option[]> => {
    const list = await searchInstruments(input)
    return list.slice(0, 50).map(toOption)
  }

  return (
    <AsyncSelect<Option>
      cacheOptions
      defaultOptions
      loadOptions={loadOptions}
      placeholder={placeholder ?? 'Search symbol…'}
      aria-label={ariaLabel ?? 'Search symbols'}
      onChange={(opt) => opt && onSelect(opt.value)}
      {...(clearOnSelect ? { value: null } : {})}
      classNamePrefix="stocksel"
      styles={{
        control: (base) => ({
          ...base,
          background: '#161b22',
          borderColor: '#30363d',
          minHeight: compact ? 34 : 44,
        }),
        menu: (base) => ({ ...base, background: '#161b22', zIndex: 50 }),
        input: (base) => ({ ...base, color: '#e6edf3' }),
        singleValue: (base) => ({ ...base, color: '#e6edf3' }),
        placeholder: (base) => ({ ...base, color: '#8b949e', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }),
        option: (base, state) => ({
          ...base,
          background: state.isFocused ? '#21262d' : 'transparent',
          color: '#e6edf3',
          cursor: 'pointer',
        }),
      }}
    />
  )
}
