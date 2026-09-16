import { describe, it, expect } from 'vitest'
import { inPeriod, dailyExpense, monthlyTrend, expenseByCategory, periodStart } from '../chartData'
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
  it('monthlyTrend buckets by natural month with zero fill (12 buckets ascending)', () => {
    // 口径修正后：固定输出最近 12 个自然月（含当月），无账单月份补 0，升序
    const t = monthlyTrend([bill(1, now, 5, 'EXPENSE'), bill(2, now, 10, 'INCOME')], now)
    expect(t.length).toBe(12)
    expect(t[11].name).toBe('2026年8月')
    expect(t[11].expense).toBe(5)
    expect(t[11].income).toBe(10)
    expect(t[10].expense).toBe(0)
  })

  // ── 自然月口径锁定（硬伤修复：30 天滚动窗造成的月漂移）──
  it('monthlyTrend 窗口锚定在 11 个月前的当月 1 日，不丢首月也不串月', () => {
    // now = 2026-08-15 → 窗口起点 = 2025-09-01 0 点（而非 30 天滚动窗的 2025-08-20 前后）
    const ref = new Date(2026, 7, 15, 12).getTime()
    const t = monthlyTrend([
      bill(1, new Date(2025, 8, 5).getTime(), 100, 'EXPENSE'),  // 2025-09-05，首月内 → 计入
      bill(2, new Date(2025, 7, 31).getTime(), 200, 'EXPENSE'), // 2025-08-31，窗口外 → 排除
      bill(3, new Date(2026, 7, 14).getTime(), 300, 'EXPENSE'), // 2026-08-14 → 当月
    ], ref)
    expect(t[0].name).toBe('2025年9月')
    expect(t[0].expense).toBe(100)
    expect(t.find((m) => m.name === '2025年8月')).toBeUndefined()
    expect(t[11].expense).toBe(300)
  })
  it('monthlyTrend 大月/小月边界：2月28日与3月1日各归各月', () => {
    const ref = new Date(2026, 2, 20).getTime() // 2026-03-20
    const t = monthlyTrend([
      bill(1, new Date(2026, 1, 28).getTime(), 28, 'EXPENSE'), // 2026-02-28（小月末）
      bill(2, new Date(2026, 2, 1).getTime(), 1, 'EXPENSE'),   // 2026-03-01（大月初）
    ], ref)
    expect(t.find((m) => m.name === '2026年2月')?.expense).toBe(28)
    expect(t.find((m) => m.name === '2026年3月')?.expense).toBe(1)
  })
  it('monthlyTrend 跨年不串桶：去年12月与今年1月相邻有序', () => {
    const ref = new Date(2026, 0, 15).getTime() // 2026-01-15
    const t = monthlyTrend([
      bill(1, new Date(2025, 11, 31).getTime(), 31, 'EXPENSE'), // 2025-12-31
      bill(2, new Date(2026, 0, 1).getTime(), 1, 'EXPENSE'),    // 2026-01-01
    ], ref)
    expect(t.length).toBe(12)
    expect(t[11].name).toBe('2026年1月')
    expect(t[11].expense).toBe(1)
    expect(t[10].name).toBe('2025年12月')
    expect(t[10].expense).toBe(31)
  })
  it('inPeriod week 以周一为起点（本地口径）', () => {
    const sat = new Date(2026, 7, 15).getTime() // 2026-08-15 周六
    expect(periodStart('week', sat)).toBe(new Date(2026, 7, 10).getTime()) // 本周一 08-10
    expect(inPeriod(bill(1, new Date(2026, 7, 10, 0, 0).getTime(), 5, 'EXPENSE'), 'week', sat)).toBe(true)
    expect(inPeriod(bill(2, new Date(2026, 7, 9, 23, 59).getTime(), 5, 'EXPENSE'), 'week', sat)).toBe(false) // 上周日
    const sun = new Date(2026, 7, 16, 12).getTime() // 2026-08-16 周日，本周仍是 08-10 起
    expect(periodStart('week', sun)).toBe(new Date(2026, 7, 10).getTime())
  })
  it('inPeriod year 以本年 1 月 1 日 0 点为起点', () => {
    expect(periodStart('year', now)).toBe(new Date(2026, 0, 1).getTime())
    expect(inPeriod(bill(1, new Date(2026, 0, 1, 0, 0).getTime(), 5, 'EXPENSE'), 'year', now)).toBe(true)
    expect(inPeriod(bill(2, new Date(2025, 11, 31, 23, 59).getTime(), 5, 'EXPENSE'), 'year', now)).toBe(false)
  })
  it('inPeriod month 在大月最后一天/月初 0 点边界正确', () => {
    const lastDay = new Date(2026, 2, 31, 22).getTime() // 2026-03-31（大月）
    expect(inPeriod(bill(1, new Date(2026, 2, 31, 0, 0).getTime(), 5, 'EXPENSE'), 'month', lastDay)).toBe(true)
    expect(inPeriod(bill(2, new Date(2026, 1, 28, 23, 59).getTime(), 5, 'EXPENSE'), 'month', lastDay)).toBe(false) // 2月末
    const firstTick = new Date(2026, 2, 1, 0, 0).getTime() // 3月1日 0 点整
    expect(inPeriod(bill(3, firstTick, 5, 'EXPENSE'), 'month', firstTick)).toBe(true)
  })
  it('dailyExpense 逐日补零、升序、月口径从 1 日开始', () => {
    const ref = new Date(2026, 7, 3, 21).getTime() // 2026-08-03 21:00
    const d = dailyExpense([
      bill(1, new Date(2026, 7, 3, 9).getTime(), 30, 'EXPENSE'),   // 8月3日
      bill(2, new Date(2026, 7, 1, 8).getTime(), 10, 'EXPENSE'),   // 8月1日
      bill(3, new Date(2026, 6, 31, 20).getTime(), 99, 'EXPENSE'), // 7月31日 → 月外排除
    ], 'month', ref)
    expect(d.map((x) => x.name)).toEqual(['8月1日', '8月2日', '8月3日']) // 升序且 8月2日 补零
    expect(d.map((x) => x.value)).toEqual([10, 0, 30])
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
