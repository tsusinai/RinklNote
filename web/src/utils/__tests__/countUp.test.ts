import { describe, it, expect, vi, afterEach } from 'vitest'
import { countUp } from '../countUp'

// 伪造 matchMedia：jsdom 可能未实现该 API，测试后按原样恢复
const originalMatchMedia = (window as any).matchMedia
function stubMatchMedia(matches: boolean): void {
  ;(window as any).matchMedia = vi.fn().mockReturnValue({ matches })
}
function restoreMatchMedia(): void {
  if (originalMatchMedia === undefined) delete (window as any).matchMedia
  else (window as any).matchMedia = originalMatchMedia
}

afterEach(restoreMatchMedia)

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
  it('同 key 重复调用同目标值：跳过动画直接定格', () => {
    vi.useFakeTimers()
    const el = { textContent: '' }
    countUp(el, 88, 'skip', String, 400)
    vi.runAllTimers()
    countUp(el, 88, 'skip', String, 400)
    expect(el.textContent).toBe('88')
    vi.useRealTimers()
  })
  it('from 值滑动：数据变化从上一次展示值起步，不归零重涨', () => {
    vi.useFakeTimers()
    const el = { textContent: '' }
    countUp(el, 100, 'slide', (n) => String(Math.round(n)), 100)
    vi.advanceTimersByTime(200)
    expect(el.textContent).toBe('100')
    countUp(el, 200, 'slide', (n) => String(Math.round(n)), 100)
    vi.advanceTimersByTime(16) // 第一帧：起点应为 100（上一次展示值）
    expect(Number(el.textContent)).toBeGreaterThan(100)
    vi.advanceTimersByTime(200)
    expect(el.textContent).toBe('200')
    vi.useRealTimers()
  })
  it('新调用取消进行中的 rAF，旧动画帧不再覆盖文本', () => {
    vi.useFakeTimers()
    const el = { textContent: '' }
    countUp(el, 100, 'cancel', (n) => String(Math.round(n)), 1000)
    countUp(el, 0, 'cancel', (n) => String(Math.round(n)), 0) // 立即定格并取消旧动画
    vi.advanceTimersByTime(2000)
    expect(el.textContent).toBe('0')
    vi.useRealTimers()
  })
  it('reduced-motion：直接定格终值，不启动动画', () => {
    stubMatchMedia(true)
    const el = { textContent: '' }
    countUp(el, 42, 'rm', (n) => String(Math.round(n)), 400)
    expect(el.textContent).toBe('42')
  })
})
