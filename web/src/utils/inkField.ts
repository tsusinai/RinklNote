/* ============================================================
 * inkField 墨晕热力场噪声引擎（Task 3.6，零依赖纯函数）
 * 概念：宣纸底上墨随热流缓慢晕开 ——
 *   - 域扭曲（热流 warp 场）驱动墨场：warp 场给基础噪声坐标加扰动，
 *     形成缓慢流动的墨晕形态
 *   - 一次噪声采样两层表现：墨晕主层（阈值+羽化显形）+ 低频读数作热力 tint
 *   - 调色板：墨色由组件读 --primary-ink 注入；热力 tint 用内置暖赭/冷青
 *     （不新增 CSS token，符合 UI 红线）
 * 可调参数集中顶部常量表；全部为确定性纯函数（同输入必同输出），便于单测。
 * 帧率降档复用 utils/perfTier.ts 的档位判定，本文件只给「档位 → 渲染参数」映射。
 * ============================================================ */

/** 参数结构（宽化 number，便于测试覆盖参数） */
export interface InkParams {
  seed: number
  baseScale: number
  warpScale: number
  warpAmp: number
  octaves: number
  threshold: number
  feather: number
  inkAlphaLight: number
  inkAlphaDark: number
  heatTintAlpha: number
  heatScale: number
  driftSpeed: number
}

/** 可调参数表（2026-09-18 设计口径） */
export const INK_PARAMS: InkParams = {
  /** 噪声种子（确定性来源） */
  seed: 1337,
  /** 基础墨场频率（1/px）：数值越小墨团越大 */
  baseScale: 0.0032,
  /** 热流 warp 场频率 */
  warpScale: 0.0021,
  /** 域扭曲幅度（px） */
  warpAmp: 96,
  /** fBm 叠加层数（2~3） */
  octaves: 3,
  /** 墨显形阈值（约 75~85% 留白，保 hero 对比度） */
  threshold: 0.6,
  /** 显形羽化宽度（半宽各 0.06，柔和过渡） */
  feather: 0.12,
  /** 墨 alpha 峰值：亮色 / 暗色主题 */
  inkAlphaLight: 0.14,
  inkAlphaDark: 0.18,
  /** 热力 tint alpha 上限（≤0.05） */
  heatTintAlpha: 0.05,
  /** 热力低频读数频率 */
  heatScale: 0.0009,
  /** 热流漂移速度（px/s） */
  driftSpeed: 6,
}

/** 档位 → 渲染参数：0 默认档（视觉验收基准），降档缓冲减半、目标帧率 30→20→15fps */
export function inkTierParams(tier: 0 | 1 | 2): { bufferWidth: number; frameIntervalMs: number } {
  const map = [
    { bufferWidth: 192, frameIntervalMs: 1000 / 30 },
    { bufferWidth: 96, frameIntervalMs: 1000 / 20 },
    { bufferWidth: 48, frameIntervalMs: 1000 / 15 },
  ] as const
  return map[tier]
}

/** 离屏缓冲高度：宽 fixed、高随视口比例；竖屏上限 480 防逐像素成本失控 */
export function bufferHeight(bufferWidth: number, viewW: number, viewH: number): number {
  if (viewW <= 0) return bufferWidth
  return Math.max(8, Math.min(480, Math.round((bufferWidth * viewH) / viewW)))
}

/* ── 基础噪声 ── */

/** 整数格点哈希 → [0,1) 的确定性伪随机（32 位混合，Math.imul 保证可移植） */
export function hash2(ix: number, iy: number, seed: number): number {
  let h = Math.imul(ix, 374761393) + Math.imul(iy, 668265263) + Math.imul(seed, 1442695041)
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  h ^= h >>> 16
  return (h >>> 0) / 4294967296
}

/** smoothstep 缓动插值因子 t∈[0,1] → 平滑 S 曲线 */
export function smoothstep(t: number): number {
  const x = Math.min(1, Math.max(0, t))
  return x * x * (3 - 2 * x)
}

/** 二维 value noise：格点伪随机 + 双线性平滑插值，输出 [0,1] */
export function valueNoise(x: number, y: number, seed: number): number {
  const x0 = Math.floor(x)
  const y0 = Math.floor(y)
  const fx = x - x0
  const fy = y - y0
  const sx = smoothstep(fx)
  const sy = smoothstep(fy)
  const v00 = hash2(x0, y0, seed)
  const v10 = hash2(x0 + 1, y0, seed)
  const v01 = hash2(x0, y0 + 1, seed)
  const v11 = hash2(x0 + 1, y0 + 1, seed)
  return v00 + (v10 - v00) * sx + (v01 - v00) * sy + (v00 - v10 - v01 + v11) * sx * sy
}

/** fBm 分形叠加（倍频 lacunarity=2、增益 gain=0.5），归一化到 [0,1] */
export function fbm(x: number, y: number, seed: number, octaves: number): number {
  let amp = 1
  let freq = 1
  let sum = 0
  let norm = 0
  for (let i = 0; i < octaves; i++) {
    sum += valueNoise(x * freq, y * freq, seed + i * 101) * amp
    norm += amp
    amp *= 0.5
    freq *= 2
  }
  return sum / norm
}

/* ── 墨场采样 ── */

export interface InkSample {
  /** 墨显形量 0~1（阈值/羽化映射后的连续值） */
  ink: number
  /** 热力读数 0~1（低频，供 tint 调色） */
  heat: number
}

/** 阈值/羽化映射：v < 阈值-半羽 → 0；v > 阈值+半羽 → 1；阈值为 0.5；
 *  羽化钳制 ≥0.02 防 edge 相等除零。 */
export function inkVisibility(v: number, threshold: number, feather: number): number {
  const half = Math.max(feather, 0.02) / 2
  return smoothstep((v - (threshold - half)) / (half * 2))
}

/**
 * 墨场单点采样（确定性）：热流 warp 扰动基础噪声坐标 → 墨场值；
 * 另取一份低频读数作热力（等温线隐喻）。t 为累计漂移时间（秒）。
 */
export function sampleInkField(x: number, y: number, t: number, params = INK_PARAMS): InkSample {
  const ox = t * params.driftSpeed
  const oy = t * params.driftSpeed * 0.6 // 漂移方向略偏斜，避免纯水平匀速感
  // 热流 warp 场（两路 fbm 合成偏移向量，-1..1）
  const wx = fbm((x + ox) * params.warpScale, (y + oy) * params.warpScale, params.seed + 11, 2) * 2 - 1
  const wy = fbm((x + ox) * params.warpScale, (y + oy) * params.warpScale, params.seed + 23, 2) * 2 - 1
  // 域扭曲后的墨场
  const sx = x + wx * params.warpAmp
  const sy = y + wy * params.warpAmp
  const v = fbm(sx * params.baseScale, sy * params.baseScale, params.seed, params.octaves)
  const ink = inkVisibility(v, params.threshold, params.feather)
  // 低频热力读数（慢于墨场半速漂移）
  const heat = fbm((x + ox * 0.4) * params.heatScale, y * params.heatScale, params.seed + 57, 2)
  return { ink, heat }
}

/* ── 调色板 ── */

/** 内置热力双色：冷青（低温）→ 暖赭（高温），RGB 数组 */
export const HEAT_COLD: readonly [number, number, number] = [79, 168, 184]
export const HEAT_WARM: readonly [number, number, number] = [201, 123, 63]

/** heat∈[0,1] 在冷青↔暖赭间线性插值，输出 rgb 三元组 */
export function mixHeatColor(heat: number): [number, number, number] {
  const h = Math.min(1, Math.max(0, heat))
  return [
    Math.round(HEAT_COLD[0] + (HEAT_WARM[0] - HEAT_COLD[0]) * h),
    Math.round(HEAT_COLD[1] + (HEAT_WARM[1] - HEAT_COLD[1]) * h),
    Math.round(HEAT_COLD[2] + (HEAT_WARM[2] - HEAT_COLD[2]) * h),
  ]
}

/** #RRGGBB → [r,g,b]；解析失败返回 null */
export function hexToRgb(hex: string): [number, number, number] | null {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return null
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/**
 * 单像素合成：墨层（--primary-ink × ink × 峰值 alpha）压在热力 tint（≤0.05）之上，
 * 源 over 混合输出 [r,g,b,a]。ink=1 时跳过 tint 采样近似（aI 覆盖）。
 */
export function composePixel(
  s: InkSample, inkRgb: readonly [number, number, number], dark: boolean, params = INK_PARAMS,
): [number, number, number, number] {
  const peak = dark ? params.inkAlphaDark : params.inkAlphaLight
  const aI = s.ink * peak
  const [hr, hg, hb] = mixHeatColor(s.heat)
  const aH = params.heatTintAlpha * s.heat * (1 - s.ink)
  // 源 over：先 tint 后墨
  const outA = aH + aI * (1 - aH)
  if (outA <= 0.0005) return [0, 0, 0, 0]
  const r = (hr * aH * (1 - aI) + inkRgb[0] * aI) / outA
  const g = (hg * aH * (1 - aI) + inkRgb[1] * aI) / outA
  const b = (hb * aH * (1 - aI) + inkRgb[2] * aI) / outA
  return [Math.round(r), Math.round(g), Math.round(b), Math.min(1, outA)]
}
