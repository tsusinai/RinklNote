import { describe, it, expect } from 'vitest'
import { formatMoney, formatSigned } from '../format'

describe('formatMoney', () => {
  it('formats with thousands separator and 2 decimals', () => {
    expect(formatMoney(1234.5)).toBe('1,234.50')
    expect(formatMoney(0)).toBe('0.00')
    expect(formatMoney(-1234.5)).toBe('-1,234.50')
  })
  it('dedupes sign on negative expense', () => {
    expect(formatSigned(-1234.5)).toBe('-1,234.50')
    expect(formatSigned(1234.5)).toBe('+1,234.50')
  })
})
