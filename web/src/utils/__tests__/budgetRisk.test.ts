import { describe, it, expect } from 'vitest'
import {
  riskLevel, predictBurnMinor, riskPct, assessBudgetRisk, monthExpenseSoFar,
  daysInMonth, daysElapsed, displayPct,
} from '../budgetRisk'
import type { Bill } from '../../types'

/* 预算烧穿风险单测（Task 4.1）：档位边界钉死 —— <85 低 / 85~100 中（含边界）/ >100 高。 */

let seq = 0
function bill(p: Partial<Bill> & { date: number; amountMinor: number }): Bill {
  return {
    id: ++seq, billType: 'EXPENSE', categoryId: 1, categoryName: '餐饮', subCategoryName: null,
    accountId: 1, remark: null, source: 'WEB', createdAt: p.date, updatedAt: null, deleted: false,
    ...p,
  }
}

describe('riskLevel 档位边界（钉死口径）', () => {
  it('低风险：pct < 85', () => {
    expect(riskLevel(0)).toBe('low')
    expect(riskLevel(84.99)).toBe('low')
  })
  it('中风险：85 ≤ pct ≤ 100（85 与 100 都含边界）', () => {
    expect(riskLevel(85)).toBe('mid')
    expect(riskLevel(99.9)).toBe('mid')
    expect(riskLevel(100)).toBe('mid')
  })
  it('高风险：pct > 100', () => {
    expect(riskLevel(100.01)).toBe('high')
    expect(riskLevel(150)).toBe('high')
  })
})

describe('燃烧速度外推', () => {
  const NOW = new Date(2026, 8, 10, 12, 0, 0).getTime() // 9 月 10 日，30 天月
  it('日均消耗 × 当月天数', () => {
    expect(daysElapsed(NOW)).toBe(10)
    expect(daysInMonth(NOW)).toBe(30)
    expect(predictBurnMinor(30000, NOW)).toBe(90000) // 30000/10 × 30
  })
  it('月末最后一天收敛为已发生支出', () => {
    const last = new Date(2026, 8, 30, 12).getTime()
    expect(predictBurnMinor(50000, last)).toBe(50000)
  })
  it('月初第 1 天防除零：外推 = 已支出 × 当月天数', () => {
    const first = new Date(2026, 8, 1, 8).getTime()
    expect(predictBurnMinor(1000, first)).toBe(30000)
  })
  it('风险占比与评估档位贯通（150% → high）', () => {
    expect(riskPct(90000, 60000)).toBeCloseTo(150)
    const a = assessBudgetRisk(60000, 30000, NOW)
    expect(a.level).toBe('high')
    expect(a.pct).toBe(150)
  })
  it('恰好压线 85% → mid（边界值单测）', () => {
    // daysElapsed=10, daysInMonth=30 → predicted = expense×3；budget = expense×3.529… 难精确
    // 改用 20 天的月份构造：predicted = expense × 30/20 = 1.5×expense；budget = expense×(150/85)
    const now20 = new Date(2026, 3, 20, 12).getTime() // 4 月 20 日，30 天
    const expense = 17000
    const budget = Math.round((expense * 1.5 * 100) / 85) // pct 恰 85
    const a = assessBudgetRisk(budget, expense, now20)
    expect(a.pct).toBeCloseTo(85, 6)
    expect(a.level).toBe('mid')
  })
  it('闰年 2 月 29 天（2028）', () => {
    expect(daysInMonth(new Date(2028, 1, 10).getTime())).toBe(29)
    expect(daysInMonth(new Date(2026, 1, 10).getTime())).toBe(28)
  })
})

describe('monthExpenseSoFar 本月已发生支出', () => {
  const NOW = new Date(2026, 8, 18, 12).getTime()
  it('只累计本月 EXPENSE，剔除收入 / 上月 / 未来 / 已删', () => {
    const bills = [
      bill({ date: new Date(2026, 8, 3).getTime(), amountMinor: 1000 }),
      bill({ date: new Date(2026, 8, 5).getTime(), amountMinor: 2000, billType: 'INCOME' }),
      bill({ date: new Date(2026, 7, 30).getTime(), amountMinor: 3000 }), // 上月
      bill({ date: new Date(2026, 8, 20).getTime(), amountMinor: 4000 }), // 未来
      bill({ date: new Date(2026, 8, 6).getTime(), amountMinor: 5000, deleted: true }),
    ]
    expect(monthExpenseSoFar(bills, NOW)).toBe(1000)
  })
})

describe('displayPct 横幅展示取整（与 App BurnRiskBar pct.toInt() 对齐）', () => {
  it('截断取整：小数部分一律舍去，不四舍五入', () => {
    expect(displayPct(85)).toBe(85)
    expect(displayPct(99.9)).toBe(99) // toFixed(0) 会得 100，App 端 toInt 是 99
    expect(displayPct(85.7)).toBe(85)
    expect(displayPct(123.45)).toBe(123)
  })
  it('横幅仅在中/高风险展示（pct ≥ 85），截断不产生越界档位文案', () => {
    // 99.99% 截断成 99% 仍是中风险语义；档位判定用未取整 pct，两者不冲突
    expect(riskLevel(99.99)).toBe('mid')
    expect(displayPct(99.99)).toBe(99)
  })
})
