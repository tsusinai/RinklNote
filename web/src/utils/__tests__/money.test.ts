import { describe, it, expect } from 'vitest'
import { formatMoney, formatMoneyPlain, formatSigned, parseMoneyToMinor } from '../money'

describe('parseMoneyToMinor（元字符串 → 分整数）', () => {
  it('不再有 parseFloat(x)*100 的浮点误差', () => {
    // 若用 parseFloat('57.97') * 100 会得到 5796.999999999999
    expect(parseMoneyToMinor('57.97')).toBe(5797)
    expect(parseMoneyToMinor('0.1')).toBe(10)
    expect(parseMoneyToMinor('0.07')).toBe(7)
    expect(parseMoneyToMinor('1.005')).toBeNull() // 超过 2 位小数：显式拒绝而非静默截断
    expect(parseMoneyToMinor('29.99')).toBe(2999)
    expect(parseMoneyToMinor('1234567.89')).toBe(123456789)
  })

  it('整数与 1 位小数输入', () => {
    expect(parseMoneyToMinor('1')).toBe(100)
    expect(parseMoneyToMinor('1.5')).toBe(150)
    expect(parseMoneyToMinor('0')).toBe(0)
  })

  it('容忍首尾空白', () => {
    expect(parseMoneyToMinor('  12.30  ')).toBe(1230)
  })

  it('非法输入一律返回 null', () => {
    for (const bad of ['', '  ', 'abc', '-5', '1.2.3', '1,234', '1.', '.5', '+3', '１２']) {
      expect(parseMoneyToMinor(bad), `input=${JSON.stringify(bad)}`).toBeNull()
    }
  })
})

describe('formatMoney（分整数 → 展示串）', () => {
  it('与 parseMoneyToMinor 互为逆运算（整数分往返无损）', () => {
    for (const yuan of ['0', '0.01', '0.1', '57.97', '1234.56', '99999999.99']) {
      const minor = parseMoneyToMinor(yuan)!
      const shown = formatMoney(minor).replace('¥', '').replace(/,/g, '')
      expect(shown).toBe(Number(yuan).toFixed(2))
    }
  })

  it('签名与千分位', () => {
    expect(formatMoney(0)).toBe('¥0.00')
    expect(formatMoney(-1)).toBe('-¥0.01')
    expect(formatMoney(100000000)).toBe('¥1,000,000.00')
    expect(formatSigned(0)).toBe('+¥0.00')
    expect(formatMoneyPlain(123456)).toBe('1,234.56')
  })
})
