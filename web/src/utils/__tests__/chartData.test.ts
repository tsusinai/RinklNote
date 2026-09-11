import { describe, it, expect } from 'vitest'
import { inPeriod, dailyExpense, monthlyTrend, expenseByCategory } from '../chartData'
import type { Bill } from '../../types'

function bill(id: number, date: number, amountMinor: number, billType: 'EXPENSE' | 'INCOME', categoryName = '三餐'): Bill {
  return { id, amountMinor, billType, categoryId: 1, categoryName, subCategoryName: null, accountId: 1, remark: null, date, source: 'WEB', createdAt: date, updatedAt: date, deleted: false }
}

describe('chartData', () => {
  const now = new Date(2026, 7, 15).getTime() // 2026-08-15
  it('inPeriod month covers current calendar month', () => {
    expect(inPeriod(bill(1, new Date(2026, 7, 1).getTime(), 5, 'EXPENSE'), 'month', now)).toBe(true)
    expect(inPeriod(bill(2, new Date(2026, 6, 31).getTime(), 5, 'EXPENSE'), 'month', now)).toBe(false)
  })
  it('dailyExpense buckets dayStart→today', () => {
    // 参考时间取当天 23:59：dailyExpense 的窗口上界是 now，若传午夜会把当天 9 点的账单排除。
    const ref = new Date(2026, 7, 15, 23, 59).getTime()
    const d = dailyExpense([bill(1, new Date(2026, 7, 15, 9).getTime(), 20, 'EXPENSE')], 'week', ref)
    expect(d.reduce((s, x) => s + x.value, 0)).toBe(20)
  })
  it('monthlyTrend returns sorted buckets', () => {
    const t = monthlyTrend([bill(1, now, 5, 'EXPENSE'), bill(2, now, 10, 'INCOME')], now)
    expect(t.length).toBeGreaterThan(0)
    expect(t[0].expense).toBe(5)
  })
  it('expenseByCategory excludes income and non-period', () => {
    const e = expenseByCategory([bill(1, new Date(2026, 7, 10).getTime(), 20, 'EXPENSE', '三餐'), bill(2, new Date(2026, 7, 10).getTime(), 99, 'INCOME')], 'month', now)
    expect(e.find((x) => x.name === '三餐')?.value).toBe(20)
    expect(e.find((x) => x.name === '三餐')?.value).not.toBe(119)
  })
  it('聚合结果为整数分，累加不产生浮点漂移', () => {
    // 0.1 + 0.2 元的经典场景：以「分」计算必须精确等于 30，而不是 30.000000000000004
    const bills = [
      bill(1, new Date(2026, 7, 10).getTime(), 10, 'EXPENSE'),
      bill(2, new Date(2026, 7, 10).getTime(), 20, 'EXPENSE'),
    ]
    const total = expenseByCategory(bills, 'month', now).find((x) => x.name === '三餐')?.value
    expect(total).toBe(30)
    expect(Number.isInteger(total)).toBe(true)
  })
})
