import { describe, it, expect } from 'vitest'
import { demoDays, demoAccounts, demoBudget } from '../../mock/demo-data'

describe('landing derived', () => {
  it('总支出动画值 = 今日 + 昨日 totals（演示用）', () => {
    // Hero 的 count-up 目标取 demoDays 前两项的 total 之和（今日 68.5 + 昨日 342）
    const recent = demoDays.slice(0, 2)
    const sum = recent.reduce((s, d) => s + d.total, 0)
    expect(sum).toBeCloseTo(410.5)
  })
  it('净资 = 资产 - 负债', () => {
    const asset = demoAccounts.filter((a) => a.kind === 'asset').reduce((s, a) => s + a.balance, 0)
    const liability = demoAccounts.filter((a) => a.kind === 'liability').reduce((s, a) => s + a.balance, 0)
    expect(asset - liability).toBeGreaterThan(0)
  })
  it('预算进度百分比在 0-100', () => {
    const pct = Math.round((demoBudget.spent / demoBudget.total) * 100)
    expect(pct).toBeGreaterThanOrEqual(0)
    expect(pct).toBeLessThanOrEqual(100)
  })
})
