import { describe, it, expect } from 'vitest'
import { fmtDate, fmtDateTime, toMonthStr, monthStart, nextMonthStart } from '../date'

describe('date utils', () => {
  const D = new Date(2026, 7, 5, 12, 30, 0).getTime() // 2026-08-05 12:30
  it('fmtDate renders 8月5日', () => { expect(fmtDate(D)).toBe('8月5日') })
  it('fmtDateTime renders 8月5日 12:30', () => { expect(fmtDateTime(D)).toBe('8月5日 12:30') })
  it('toMonthStr renders 2026-08', () => { expect(toMonthStr(D)).toBe('2026-08') })
  it('monthStart and nextMonthStart are first-of-month epoch ms', () => {
    expect(monthStart(D).toString()).toBe(new Date(2026, 7, 1).getTime().toString())
    expect(nextMonthStart(D).toString()).toBe(new Date(2026, 8, 1).getTime().toString())
  })
})
