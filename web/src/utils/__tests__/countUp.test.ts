import { describe, it, expect, vi } from 'vitest'
import { countUp } from '../countUp'

describe('countUp', () => {
  it('skips to final value when key already at target', () => {
    const el = { textContent: '' }
    countUp(el, 100, 'a', String, 0)
    countUp(el, 100, 'a', String, 0)
    expect(el.textContent).toBe('100')
  })
  it('reaches final value', () => {
    vi.useFakeTimers()
    const el = { textContent: '' }
    countUp(el, 50, 'b', (n) => String(Math.round(n)), 0)
    vi.runAllTimers()
    expect(el.textContent).toBe('50')
    vi.useRealTimers()
  })
})
