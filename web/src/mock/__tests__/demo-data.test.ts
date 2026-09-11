import { describe, it, expect } from 'vitest'
import { demoDays, demoAccounts, demoBudget, demoInsight } from '../demo-data'

describe('demo-data', () => {
  it('demoDays groups bills with a total and a label', () => {
    expect(demoDays.length).toBeGreaterThan(0)
    for (const d of demoDays) {
      expect(typeof d.date).toBe('string')
      expect(typeof d.label).toBe('string')
      expect(d.bills.length).toBeGreaterThan(0)
      // totalMinor = 当日各笔 amountMinor 之和（均为「分」整数）
      expect(d.totalMinor).toBe(d.bills.reduce((s, b) => s + b.amountMinor, 0))
    }
  })
  it('demoAccounts has both asset and liability kinds', () => {
    expect(demoAccounts.some((a) => a.kind === 'asset')).toBe(true)
    expect(demoAccounts.some((a) => a.kind === 'liability')).toBe(true)
    for (const a of demoAccounts) expect(a.balanceMinor).toBeGreaterThanOrEqual(0)
  })
  it('demoBudget spent does not exceed total', () => {
    expect(demoBudget.spentMinor).toBeLessThanOrEqual(demoBudget.totalMinor)
    expect(demoBudget.byCategory.every((c) => Number.isInteger(c.amountMinor))).toBe(true)
  })
  it('demoInsight has a summary and highlights', () => {
    expect(demoInsight.summary.length).toBeGreaterThan(0)
    expect(demoInsight.highlights.length).toBeGreaterThan(0)
  })
})
