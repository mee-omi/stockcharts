import AsyncSelect from 'react-select/async'
import type { Instrument } from '../api/types'
import { searchInstruments } from '../api/client'

interface Option {
  value: string
  label: string
  instrument: Instrument
}

function toOption(i: Instrument): Option {
  return { value: i.symbol, label: `${i.symbol} — ${i.name}`, instrument: i }
}

interface Props {
  onSelect: (symbol: string) => void
}

export default function StockSearch({ onSelect }: Props) {
  const loadOptions = async (input: string): Promise<Option[]> => {
    const list = await searchInstruments(input)
    return list.slice(0, 50).map(toOption)
  }

  return (
    <AsyncSelect<Option>
      cacheOptions
      defaultOptions
      loadOptions={loadOptions}
      placeholder="Search NSE stocks (e.g. RELIANCE, Infosys)…"
      onChange={(opt) => opt && onSelect(opt.value)}
      classNamePrefix="stocksel"
      styles={{
        control: (base) => ({
          ...base,
          background: '#161b22',
          borderColor: '#30363d',
          minHeight: 44,
        }),
        menu: (base) => ({ ...base, background: '#161b22', zIndex: 50 }),
        input: (base) => ({ ...base, color: '#e6edf3' }),
        singleValue: (base) => ({ ...base, color: '#e6edf3' }),
        placeholder: (base) => ({ ...base, color: '#8b949e' }),
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
