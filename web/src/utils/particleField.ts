/* ============================================================
 * 粒子星空引擎（纯 TS，与 DOM / Canvas 完全解耦，便于单测）
 * 职责边界：本文件只负责「粒子的数值状态推进」，不碰任何渲染 API；
 * 绘制与生命周期在 components/ParticleBackground.vue。
 *
 * 物理模型（2026-09-18 用户反馈「不明显」后加浓：密度/速度/半径上提，仍不做连线）：
 * - 密度：clamp(面积/9000, 70, 220) 个粒子，半径 1.0~2.8px
 * - 漂移：每粒子恒定基础速度 12~30 px/s（随机方向），出界环绕
 * - 闪烁：每粒子 2~6s 周期的相位推进，渲染层据此调制 alpha（星点呼吸感）
 * - 斥力：指针半径 ~110px 内、力度随距离线性衰减，冲量记入 pv*，
 *   并按 0.92（60fps 基准）逐帧阻尼衰减 —— 粒子被轻推后回落漂移；
 *   只斥不吸、不做连线
 * ============================================================ */

/** 单个粒子：位置/半径 + 漂移速度 + 斥力冲量速度 + 闪烁相位 + 渲染因子 */
export interface Particle {
  x: number
  y: number
  r: number
  /** 基础漂移速度（px/s），恒定不变，出界环绕 */
  dvx: number
  dvy: number
  /** 指针斥力冲量速度（px/s），逐帧阻尼衰减回落 */
  pvx: number
  pvy: number
  /** 闪烁相位（弧度），随时间推进 */
  phase: number
  /** 闪烁角速度（rad/s），对应 2~6s 周期 */
  twinkleSpeed: number
  /** 固有亮度因子 0~1（渲染层据此在主题 alpha 区间内取值） */
  glow: number
  /** 色调：0 = 主题主色，1 = 点缀色（亮色=灰蓝 / 暗色=近白，由渲染层按主题解释） */
  tone: 0 | 1
}

/** 指针位置（CSS 像素，与画布 CSS 尺寸同一坐标系） */
export interface Pointer {
  x: number
  y: number
}

/** 粒子场：尺寸 + 全部粒子 + 斥力参数 */
export interface ParticleField {
  w: number
  h: number
  /** 累计推进时间（秒），预留调试/扩展用 */
  time: number
  particles: Particle[]
  /** 斥力半径（px） */
  repelRadius: number
  /** 斥力加速度（px/s²），距指针越近越大（线性衰减） */
  repelForce: number
  /** 冲量阻尼系数（以 60fps 单帧为基准） */
  damping: number
}

const MIN_COUNT = 70
const MAX_COUNT = 220
/** 密度分母：每 9000px² 一个粒子 */
const AREA_PER_PARTICLE = 9000

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

/** 密度公式：clamp(round(面积/9000), 70, 220) —— 渲染层 resize 与测试共用 */
export function densityCount(w: number, h: number): number {
  return clamp(Math.round((w * h) / AREA_PER_PARTICLE), MIN_COUNT, MAX_COUNT)
}

function rand(min: number, max: number): number {
  return min + Math.random() * (max - min)
}

/** 造一个粒子：随机位置/半径/漂移方向/闪烁相位；约 15% 为点缀色 */
function makeParticle(w: number, h: number): Particle {
  const speed = rand(12, 30) // 漂移速率 12~30 px/s
  const angle = rand(0, Math.PI * 2)
  return {
    x: rand(0, w),
    y: rand(0, h),
    r: rand(1.0, 2.8),
    dvx: Math.cos(angle) * speed,
    dvy: Math.sin(angle) * speed,
    pvx: 0,
    pvy: 0,
    phase: rand(0, Math.PI * 2),
    twinkleSpeed: (Math.PI * 2) / rand(2, 6), // 周期 2~6s
    glow: rand(0.15, 1),
    tone: Math.random() < 0.15 ? 1 : 0,
  }
}

/** 建场：按密度公式铺满 w×h */
export function createField(w: number, h: number): ParticleField {
  const particles: Particle[] = []
  const count = densityCount(w, h)
  for (let i = 0; i < count; i++) particles.push(makeParticle(w, h))
  return {
    w,
    h,
    time: 0,
    particles,
    repelRadius: 110,
    repelForce: 220,
    damping: 0.92,
  }
}

/** 尺寸变化：按新面积增删粒子（尽量保留现存粒子），越界粒子收回新边界 */
export function resizeField(f: ParticleField, w: number, h: number): void {
  f.w = w
  f.h = h
  const target = densityCount(w, h)
  while (f.particles.length > target) f.particles.pop()
  while (f.particles.length < target) f.particles.push(makeParticle(w, h))
  for (const p of f.particles) {
    // 取模环绕回新边界，避免 resize 后粒子长时间滞留在可视区外
    p.x = ((p.x % w) + w) % w
    p.y = ((p.y % h) + h) % h
  }
}

/**
 * 单步推进 dt 秒（渲染层以 rAF 帧间隔调用；dt 建议钳制 ≤0.05s 防切后台跳变）。
 * 顺序：闪烁相位 → 指针斥力冲量 → 冲量阻尼 → 位移（漂移 + 冲量）→ 出界环绕。
 * pointer 缺省（指针离开窗口）时只做漂移与闪烁。
 */
export function stepField(f: ParticleField, dt: number, pointer?: Pointer): void {
  if (dt <= 0) return
  f.time += dt
  // 帧率无关的阻尼折算：0.92 为 60fps 单帧基准
  const decay = Math.pow(f.damping, dt * 60)
  const radius = f.repelRadius
  const radiusSq = radius * radius
  for (const p of f.particles) {
    // 闪烁相位推进（渲染层用 twinkleOf 换算 alpha）
    p.phase += p.twinkleSpeed * dt
    // 指针斥力：半径内沿「粒子 − 指针」方向叠加冲量（只斥不吸）
    if (pointer) {
      const dx = p.x - pointer.x
      const dy = p.y - pointer.y
      const distSq = dx * dx + dy * dy
      if (distSq < radiusSq && distSq > 1e-4) {
        const dist = Math.sqrt(distSq)
        const accel = f.repelForce * (1 - dist / radius) // 距离线性衰减
        p.pvx += (dx / dist) * accel * dt
        p.pvy += (dy / dist) * accel * dt
      }
    }
    // 冲量阻尼：被轻推后逐渐回落到基础漂移
    p.pvx *= decay
    p.pvy *= decay
    // 位移 = 基础漂移 + 冲量
    p.x += (p.dvx + p.pvx) * dt
    p.y += (p.dvy + p.pvy) * dt
    // 出界环绕（留半径余量，星点不会在边缘生硬闪没）
    const m = p.r
    if (p.x < -m) p.x = f.w + m
    else if (p.x > f.w + m) p.x = -m
    if (p.y < -m) p.y = f.h + m
    else if (p.y > f.h + m) p.y = -m
  }
}

/** 闪烁系数 0.4~1（呼吸感加深）：渲染层用「区间内取 alpha」时乘到 glow 上 */
export function twinkleOf(p: Particle): number {
  const s = Math.sin(p.phase) // -1..1
  return 0.4 + 0.6 * ((s + 1) / 2)
}
