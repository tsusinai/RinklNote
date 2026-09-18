import { describe, it, expect } from 'vitest'
import {
  INK_PARAMS, inkTierParams, bufferHeight, hash2, valueNoise, fbm,
  inkVisibility, sampleInkField, mixHeatColor, composePixel,
  HEAT_COLD, HEAT_WARM,
} from '../inkField'

/* inkField 墨晕热力场单测（Task 3.6）：噪声确定性、值域、阈值/羽化映射、档位映射与像素合成。 */

describe('噪声确定性', () => {
  it('hash2 同输入同输出，且值域 [0,1)', () => {
    expect(hash2(12, 34, INK_PARAMS.seed)).toBe(hash2(12, 34, INK_PARAMS.seed))
    for (let i = 0; i < 200; i++) {
      const v = hash2(i, i * 7 + 3, 99)
      expect(v).toBeGreaterThanOrEqual(0)
      expect(v).toBeLessThan(1)
    }
  })
  it('sampleInkField 完全确定：同坐标同时间两次采样一致', () => {
    const a = sampleInkField(123.4, 567.8, 12.5)
    const b = sampleInkField(123.4, 567.8, 12.5)
    expect(a.ink).toBe(b.ink)
    expect(a.heat).toBe(b.heat)
  })
  it('时间推进改变采样（热流漂移可见）', () => {
    const a = sampleInkField(400, 300, 0)
    const b = sampleInkField(400, 300, 30)
    expect(a.ink === b.ink && a.heat === b.heat).toBe(false)
  })
  it('不同种子产生不同场', () => {
    const a = sampleInkField(100, 100, 5, { ...INK_PARAMS, seed: 1 })
    const b = sampleInkField(100, 100, 5, { ...INK_PARAMS, seed: 2 })
    expect(a.ink === b.ink && a.heat === b.heat).toBe(false)
  })
})

describe('值域与形态', () => {
  it('valueNoise / fbm 输出 [0,1]', () => {
    for (let i = 0; i < 300; i++) {
      const x = i * 7.3
      const y = i * 3.1
      const n = valueNoise(x, y, 7)
      expect(n).toBeGreaterThanOrEqual(0)
      expect(n).toBeLessThanOrEqual(1)
      const f = fbm(x, y, 7, INK_PARAMS.octaves)
      expect(f).toBeGreaterThanOrEqual(0)
      expect(f).toBeLessThanOrEqual(1)
    }
  })
  it('valueNoise 整数格点处值 = 格点哈希（插值端点）', () => {
    expect(valueNoise(3, 4, 9)).toBe(hash2(3, 4, 9))
  })
})

describe('阈值/羽化映射（0.60 + 0.12）', () => {
  const TH = INK_PARAMS.threshold
  const FE = INK_PARAMS.feather
  it('低于阈值-半羽 → 0（留白 ≥75%）', () => {
    expect(inkVisibility(TH - FE / 2 - 0.01, TH, FE)).toBe(0)
    expect(inkVisibility(0.3, TH, FE)).toBe(0)
  })
  it('高于阈值+半羽 → 1（完全显形）', () => {
    expect(inkVisibility(TH + FE / 2 + 0.01, TH, FE)).toBe(1)
    expect(inkVisibility(0.9, TH, FE)).toBe(1)
  })
  it('阈值处恰好 0.5，区间内单调', () => {
    expect(inkVisibility(TH, TH, FE)).toBeCloseTo(0.5, 10)
    let prev = 0
    for (let v = 0.5; v <= 0.7; v += 0.005) {
      const out = inkVisibility(v, TH, FE)
      expect(out).toBeGreaterThanOrEqual(prev)
      prev = out
    }
  })
  it('羽化宽度控制过渡带', () => {
    // 更窄羽化：距阈值稍偏即快速到 1
    expect(inkVisibility(TH + 0.02, TH, 0.04)).toBeGreaterThan(inkVisibility(TH + 0.02, TH, FE))
  })
})

describe('档位映射（复用 perfTier 判定）', () => {
  it('默认档 192px / 30fps；降档缓冲减半、30→20→15fps', () => {
    expect(inkTierParams(0)).toEqual({ bufferWidth: 192, frameIntervalMs: 1000 / 30 })
    expect(inkTierParams(1).bufferWidth).toBe(96)
    expect(inkTierParams(1).frameIntervalMs).toBeCloseTo(50)
    expect(inkTierParams(2).bufferWidth).toBe(48)
    expect(inkTierParams(2).frameIntervalMs).toBeCloseTo(1000 / 15)
  })
  it('缓冲高度随视口比例、竖屏有上限', () => {
    expect(bufferHeight(192, 1920, 1080)).toBe(108)
    expect(bufferHeight(192, 390, 844)).toBeLessThanOrEqual(480)
    expect(bufferHeight(192, 0, 100)).toBe(192) // 防除零
  })
})

describe('调色板与像素合成', () => {
  it('heat 端点 = 冷青 / 暖赭，中点居中', () => {
    expect(mixHeatColor(0)).toEqual([...HEAT_COLD])
    expect(mixHeatColor(1)).toEqual([...HEAT_WARM])
    const mid = mixHeatColor(0.5)
    expect(mid[0]).toBe(Math.round((HEAT_COLD[0] + HEAT_WARM[0]) / 2))
  })
  it('墨 alpha 峰值亮 0.14 / 暗 0.18；全显形处输出墨色', () => {
    const inkRgb: [number, number, number] = [40, 92, 130]
    const full = { ink: 1, heat: 0.9 }
    const light = composePixel(full, inkRgb, false)
    const dark = composePixel(full, inkRgb, true)
    expect(light[3]).toBeCloseTo(0.14, 5)
    expect(dark[3]).toBeCloseTo(0.18, 5)
    expect(light[0]).toBe(inkRgb[0])
  })
  it('留白处只剩微量 tint（alpha ≤ 0.05）', () => {
    const out = composePixel({ ink: 0, heat: 1 }, [40, 92, 130], false)
    expect(out[3]).toBeCloseTo(INK_PARAMS.heatTintAlpha, 5)
    expect(out[3]).toBeLessThanOrEqual(0.05)
  })
})
