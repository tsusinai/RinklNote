import { describe, it, expect } from 'vitest'
import { demoDays, demoAccounts, demoBudget } from '../../mock/demo-data'

describe('landing derived', () => {
  it('总支出动画值 = 三日 totals 之和（BookkeepingDemo 的 countUp 目标）', () => {
    // BookkeepingDemo.vue 的 countUp 目标取 demoDays 全部三项的 total 之和（68.5 + 342 + 35.5 = 446）
    const sum = demoDays.reduce((s, d) => s + d.total, 0)
    expect(sum).toBeCloseTo(446)
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
