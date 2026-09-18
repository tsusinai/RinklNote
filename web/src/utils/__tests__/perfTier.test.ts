import { describe, it, expect, afterEach } from 'vitest'
import {
  classifyFrame, FrameQualityMonitor, createVisibilityController,
  DEFAULT_PERF_CONFIG, MAX_TIER, type TierDecision,
} from '../perfTier'
import { createField, applyPerfTier, densityCount, TIER_SPEED_FACTOR, TIER_DENSITY_FACTOR } from '../particleField'

/* 可复用帧率降档模块单测（Task 3.3）：帧分类、降档决策（只降不升）、冷却期、
 * 可见性暂停恢复控制器，以及档位 → 粒子密度/速度映射的幂等性。 */

function slowMs(): number { return DEFAULT_PERF_CONFIG.slowFrameMs + 5 }
function okMs(): number { return 16.7 }

describe('classifyFrame 帧分类', () => {
  it('正常帧（60fps）→ ok', () => {
    expect(classifyFrame(16.7)).toBe('ok')
    expect(classifyFrame(8)).toBe('ok')
  })
  it('达到慢帧阈值（<45fps）→ slow', () => {
    expect(classifyFrame(DEFAULT_PERF_CONFIG.slowFrameMs)).toBe('slow')
    expect(classifyFrame(33)).toBe('slow')
  })
  it('非法与异常帧 → ignore（不进统计窗口）', () => {
    expect(classifyFrame(0)).toBe('ignore')
    expect(classifyFrame(-1)).toBe('ignore')
    expect(classifyFrame(NaN)).toBe('ignore')
    expect(classifyFrame(DEFAULT_PERF_CONFIG.anomalyFrameMs)).toBe('ignore') // 切后台等异常大间隔
  })
})

describe('FrameQualityMonitor 降档决策', () => {
  it('窗口未满不判定：全部慢帧也不降档', () => {
    const m = new FrameQualityMonitor()
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize - 1; i++) m.push(slowMs())
    expect(m.tier).toBe(0)
  })
  it('慢帧占比达阈值 → 降一档；继续慢 → 到顶档 MAX_TIER 为止', () => {
    const m = new FrameQualityMonitor()
    // 窗口一半慢帧（占比恰好 0.5 ≥ 阈值）；窗口第 60 帧满窗即判定 → tier 1
    let d1: TierDecision = { tier: 0, changed: false }
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize / 2; i++) m.push(slowMs())
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize / 2; i++) d1 = m.push(okMs())
    expect(d1).toEqual({ tier: 1, changed: true })
    // 冷却期：连续慢帧也不跳档
    for (let i = 0; i < DEFAULT_PERF_CONFIG.cooldownFrames; i++) expect(m.push(slowMs()).changed).toBe(false)
    // 冷却期窗口仍在积帧：冷却一结束，满窗慢帧令首帧即判定 → tier 2
    const d2 = m.push(slowMs())
    expect(d2).toEqual({ tier: 2, changed: true })
    expect(MAX_TIER).toBe(2)
    // 已到顶档：再慢也不变
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize + DEFAULT_PERF_CONFIG.cooldownFrames + 5; i++) m.push(slowMs())
    expect(m.tier).toBe(2)
  })
  it('帧率恢复不升档（只降不升，防画面振荡）', () => {
    const m = new FrameQualityMonitor()
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize; i++) m.push(slowMs())
    m.push(slowMs())
    expect(m.tier).toBe(1)
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize * 3; i++) m.push(okMs())
    expect(m.tier).toBe(1)
  })
  it('reset 回到指定档位并清空窗口', () => {
    const m = new FrameQualityMonitor()
    for (let i = 0; i < DEFAULT_PERF_CONFIG.windowSize; i++) m.push(slowMs())
    m.push(slowMs())
    m.reset(0)
    expect(m.tier).toBe(0)
    expect(m.push(okMs())).toEqual({ tier: 0, changed: false })
  })
})

describe('createVisibilityController 页签隐藏暂停', () => {
  const hiddenDesc = Object.getOwnPropertyDescriptor(document, 'hidden')
  function setHidden(v: boolean): void {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => v })
  }
  afterEach(() => {
    if (hiddenDesc) Object.defineProperty(document, 'hidden', hiddenDesc)
  })

  it('切到隐藏 → onPause；切回可见 → onResume', () => {
    const calls: string[] = []
    const ctrl = createVisibilityController(() => calls.push('pause'), () => calls.push('resume'))
    setHidden(true)
    document.dispatchEvent(new Event('visibilitychange'))
    setHidden(false)
    document.dispatchEvent(new Event('visibilitychange'))
    ctrl.stop()
    expect(calls).toEqual(['pause', 'resume'])
  })
  it('stop 后不再响应 visibilitychange', () => {
    const calls: string[] = []
    const ctrl = createVisibilityController(() => calls.push('pause'), () => calls.push('resume'))
    ctrl.stop()
    setHidden(true)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(calls).toEqual([])
  })
})

describe('particleField 档位映射（默认档视觉不变）', () => {
  it('tier 0 密度与旧公式完全一致（浓密度默认档）', () => {
    expect(TIER_DENSITY_FACTOR[0]).toBe(1)
    expect(densityCount(400, 300, 0)).toBe(70)
    expect(densityCount(1920, 1080, 0)).toBe(220)
  })
  it('降档密度缩减，且上下限随档位同缩（小屏降档也生效）', () => {
    const d1 = densityCount(1920, 1080, 1)
    const d2 = densityCount(1920, 1080, 2)
    expect(d1).toBeLessThan(densityCount(1920, 1080, 0))
    expect(d2).toBeLessThan(d1)
    // 小屏已在 70 下限：tier 2 必须能降到下限×系数以下，否则降档无效
    expect(densityCount(400, 300, 2)).toBeLessThan(70)
  })
  it('applyPerfTier 幂等；切换后粒子数与漂移速度同步缩放', () => {
    const f = createField(1200, 800)
    const before = f.particles.length
    const avgSpeed = () => f.particles.reduce((s, p) => s + Math.hypot(p.dvx, p.dvy), 0) / f.particles.length
    const v0 = avgSpeed()
    expect(applyPerfTier(f, 1)).toBe(true)
    expect(f.tier).toBe(1)
    expect(f.particles.length).toBe(densityCount(1200, 800, 1))
    expect(f.particles.length).toBeLessThanOrEqual(before)
    expect(avgSpeed() / v0).toBeCloseTo(TIER_SPEED_FACTOR[1], 1)
    expect(applyPerfTier(f, 1)).toBe(false) // 幂等
    expect(applyPerfTier(f, 0)).toBe(true) // 可回切（组件重建等场景）
    expect(f.tier).toBe(0)
    expect(f.particles.length).toBe(densityCount(1200, 800, 0))
  })
  it('createField 直按档位建场', () => {
    const f = createField(1200, 800, 2)
    expect(f.tier).toBe(2)
    expect(f.particles.length).toBe(densityCount(1200, 800, 2))
  })
})
