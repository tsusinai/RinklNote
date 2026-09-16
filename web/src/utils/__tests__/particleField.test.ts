import { describe, it, expect } from 'vitest'
import { createField, resizeField, stepField, densityCount, twinkleOf } from '../particleField'

/* 粒子星空引擎单测：密度 clamp、漂移出界环绕、指针斥力方向、resize 粒子数界限。
 * 粒子状态可直接改写（纯 TS 引擎与 DOM 解耦），测试不需要随机数种子。 */

describe('densityCount 密度 clamp', () => {
  it('小面积取下限 40', () => {
    expect(densityCount(400, 300)).toBe(40) // 120000/18000 ≈ 6.7 → clamp 40
  })
  it('大面积取上限 110', () => {
    expect(densityCount(1920, 1080)).toBe(110) // 2073600/18000 ≈ 115 → clamp 110
  })
  it('中间面积按 面积/18000 四舍五入', () => {
    expect(densityCount(1800, 800)).toBe(80)
  })
  it('createField 粒子数与密度公式一致', () => {
    expect(createField(1000, 700).particles.length).toBe(densityCount(1000, 700))
  })
})

describe('漂移与出界环绕', () => {
  it('向右漂移出界后从左侧环绕回来', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 499; p.y = 200
    p.dvx = 10; p.dvy = 0
    p.pvx = 0; p.pvy = 0
    stepField(f, 1)
    // x = 499 + 10 = 509 > 500 + r → 环绕到 -r 附近（远小于 10）
    expect(p.x).toBeLessThan(10)
  })
  it('向上漂移出界后从底部环绕回来', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 250; p.y = 1
    p.dvx = 0; p.dvy = -10
    p.pvx = 0; p.pvy = 0
    stepField(f, 1)
    // y = 1 - 10 = -9 < -r → 环绕到 h + r 附近
    expect(p.y).toBeGreaterThan(395)
  })
  it('界内正常推进：位移 = 漂移速度 × dt', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 100; p.y = 100
    p.dvx = 12; p.dvy = -6
    p.pvx = 0; p.pvy = 0
    stepField(f, 0.5)
    expect(p.x).toBeCloseTo(106, 5)
    expect(p.y).toBeCloseTo(97, 5)
  })
})

describe('指针斥力', () => {
  it('方向正确：指针在右侧，粒子被向左推（只斥不吸）', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 100; p.y = 200
    p.dvx = 0; p.dvy = 0
    p.pvx = 0; p.pvy = 0
    stepField(f, 1 / 60, { x: 110, y: 200 })
    expect(p.pvx).toBeLessThan(0) // 冲量向左（远离指针）
    expect(p.x).toBeLessThan(100)
    expect(p.pvy).toBeCloseTo(0, 9) // 同一水平线上无纵向分量
  })
  it('方向正确：指针在下方，粒子被向上推', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 250; p.y = 200
    p.dvx = 0; p.dvy = 0
    p.pvx = 0; p.pvy = 0
    stepField(f, 1 / 60, { x: 250, y: 210 })
    expect(p.pvy).toBeLessThan(0) // 冲量向上（远离指针）
    expect(p.y).toBeLessThan(200)
  })
  it('斥力半径外不受影响', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 100; p.y = 200
    p.dvx = 0; p.dvy = 0
    p.pvx = 0; p.pvy = 0
    stepField(f, 1 / 60, { x: 300, y: 300 }) // 距离 > 110px
    expect(p.pvx).toBe(0)
    expect(p.pvy).toBe(0)
    expect(p.x).toBe(100)
  })
  it('阻尼回落：失去指针后冲量逐帧衰减、不会永久加速', () => {
    const f = createField(500, 400)
    const p = f.particles[0]
    p.x = 100; p.y = 200
    p.dvx = 0; p.dvy = 0
    // 借一步强冲量（近距推离）获得非零 pvx
    stepField(f, 1 / 60, { x: 101, y: 200 })
    expect(p.pvx).toBeLessThan(0)
    const afterPush = Math.abs(p.pvx)
    stepField(f, 1 / 60) // 指针已离开
    stepField(f, 1 / 60)
    expect(Math.abs(p.pvx)).toBeLessThan(afterPush)
    // 持续推进后冲量趋近 0，粒子回落为纯漂移
    for (let i = 0; i < 240; i++) stepField(f, 1 / 60)
    expect(Math.abs(p.pvx)).toBeLessThan(0.01)
  })
})

describe('resizeField 按面积增删', () => {
  it('粒子数随新面积回到界限内', () => {
    const f = createField(1920, 1080) // → 110
    expect(f.particles.length).toBe(110)
    resizeField(f, 400, 300) // → 40
    expect(f.particles.length).toBe(40)
    resizeField(f, 1920, 1080) // → 110
    expect(f.particles.length).toBe(110)
  })
  it('尽量保留现存粒子（增删而非整体重建）', () => {
    const f = createField(1920, 1080)
    const survivor = f.particles[0]
    resizeField(f, 400, 300)
    expect(f.particles).toContain(survivor)
    resizeField(f, 1920, 1080)
    expect(f.particles).toContain(survivor)
  })
  it('缩小后所有粒子都收回新边界内', () => {
    const f = createField(1920, 1080)
    resizeField(f, 400, 300)
    for (const p of f.particles) {
      expect(p.x).toBeGreaterThanOrEqual(0)
      expect(p.x).toBeLessThanOrEqual(400)
      expect(p.y).toBeGreaterThanOrEqual(0)
      expect(p.y).toBeLessThanOrEqual(300)
    }
  })
})

describe('闪烁相位', () => {
  it('相位随时间推进，twinkleOf 恒在呼吸区间 [0.55, 1]', () => {
    const f = createField(300, 300)
    const p = f.particles[0]
    const before = p.phase
    stepField(f, 0.5)
    expect(p.phase).toBeGreaterThan(before)
    for (let i = 0; i < 48; i++) {
      stepField(f, 0.125) // 覆盖若干完整 2~6s 周期
      const t = twinkleOf(p)
      expect(t).toBeGreaterThanOrEqual(0.55)
      expect(t).toBeLessThanOrEqual(1)
    }
  })
  it('dt ≤ 0 时 no-op（不推进、不位移）', () => {
    const f = createField(300, 300)
    const p = f.particles[0]
    p.dvx = 0; p.dvy = 0
    const x = p.x; const y = p.y; const phase = p.phase
    stepField(f, 0)
    stepField(f, -1)
    expect(p.x).toBe(x)
    expect(p.y).toBe(y)
    expect(p.phase).toBe(phase)
  })
})
