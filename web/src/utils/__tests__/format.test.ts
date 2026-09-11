import { describe, it, expect } from 'vitest'
import { formatMoney, formatMoneyPlain, formatSigned } from '../format'

describe('formatMoney', () => {
  it('formats 分 → ¥ + thousands separator + 2 decimals', () => {
    expect(formatMoney(123450)).toBe('¥1,234.50')
    expect(formatMoney(0)).toBe('¥0.00')
    expect(formatMoney(-123450)).toBe('-¥1,234.50')
    expect(formatMoney(7)).toBe('¥0.07')
    expect(formatMoney(5797)).toBe('¥57.97')
  })
  it('rounds animated intermediate frames (非整数分) to nearest 分', () => {
    expect(formatMoney(5796.4)).toBe('¥57.96')
    expect(formatMoney(5796.6)).toBe('¥57.97')
  })
  it('formatMoneyPlain 只输出数字部分（¥ 由模板单独渲染）', () => {
    expect(formatMoneyPlain(123450)).toBe('1,234.50')
    expect(formatMoneyPlain(0)).toBe('0.00')
  })
  it('formatSigned prefixes + / -', () => {
    expect(formatSigned(-123450)).toBe('-¥1,234.50')
    expect(formatSigned(123450)).toBe('+¥1,234.50')
  })
})
