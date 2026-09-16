import { describe, it, expect } from 'vitest'
import { formatMoney, formatMoneyPlain, formatSigned, parseMoneyToMinor, minorToDecimal } from '../money'
import { toCsv } from '../csv'

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

describe('minorToDecimal（分整数 → 纯小数串，CSV 导出/输入回显用）', () => {
  it('全程整数拆分，不出现浮点尾数', () => {
    expect(minorToDecimal(5797)).toBe('57.97')
    expect(minorToDecimal(30)).toBe('0.30')
    expect(minorToDecimal(0)).toBe('0.00')
    expect(minorToDecimal(-1)).toBe('-0.01')
    expect(minorToDecimal(2999)).toBe('29.99')
    expect(minorToDecimal(123456789)).toBe('1234567.89')
  })

  it('扫范围：输出恒为固定 2 位小数的规整串（浮点除法会出现 0.30000000000000004 类尾数）', () => {
    for (let m = 0; m <= 20000; m += 7) {
      const s = minorToDecimal(m)
      expect(s, `minor=${m}`).toMatch(/^-?\d+\.\d{2}$/)
      expect(s, `minor=${m}`).toBe((m / 100).toFixed(2)) // 结果与浮点路径一致，但本实现无浮点参与
    }
  })

  it('与 parseMoneyToMinor 往返无损', () => {
    for (const yuan of ['0.07', '57.97', '1234.56', '99999999.99']) {
      const minor = parseMoneyToMinor(yuan)!
      expect(parseMoneyToMinor(minorToDecimal(minor)), `yuan=${yuan}`).toBe(minor)
    }
  })

  it('CSV 导出链路：金额列不含浮点误差（锁死整数分契约）', () => {
    const rows: (string | number)[][] = [['类型', '金额'], ['支出', minorToDecimal(2999)], ['收入', minorToDecimal(3010)], ['支出', minorToDecimal(1)]]
    const csv = toCsv(rows)
    expect(csv).toContain('29.99')
    expect(csv).toContain('30.10')
    expect(csv).toContain('0.01')
    expect(csv).not.toMatch(/\d+\.\d{3,}/) // 任何超过 2 位小数的浮点尾数都不允许出现
  })
})
