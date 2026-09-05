import { describe, it, expect } from 'vitest'
import { demoDays, demoAccounts, demoBudget, demoInsight } from '../demo-data'

describe('demo-data', () => {
  it('demoDays groups bills with a total and a label', () => {
    expect(demoDays.length).toBeGreaterThan(0)
    for (const d of demoDays) {
      expect(typeof d.date).toBe('string')
      expect(typeof d.label).toBe('string')
      expect(d.bills.length).toBeGreaterThan(0)
      // total = sum of |amount|
      expect(d.total).toBeCloseTo(d.bills.reduce((s, b) => s + b.amount, 0))
    }
  })
  it('demoAccounts has both asset and liability kinds', () => {
    expect(demoAccounts.some((a) => a.kind === 'asset')).toBe(true)
    expect(demoAccounts.some((a) => a.kind === 'liability')).toBe(true)
    for (const a of demoAccounts) expect(a.balance).toBeGreaterThanOrEqual(0)
  })
  it('demoBudget spent does not exceed total', () => {
    expect(demoBudget.spent).toBeLessThanOrEqual(demoBudget.total)
    expect(demoBudget.byCategory.map((c) => typeof c.amount).every((t) => t === 'number')).toBe(true)
  })
  it('demoInsight has a summary and highlights', () => {
    expect(demoInsight.summary.length).toBeGreaterThan(0)
    expect(demoInsight.highlights.length).toBeGreaterThan(0)
  })
})
