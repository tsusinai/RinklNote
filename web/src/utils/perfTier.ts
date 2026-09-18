/* ============================================================
 * 可复用帧率降档模块（Task 3.3 星空 / Task 3.6 墨晕热力场共用）
 * 职责边界：本文件只回答三个问题 ——
 *   1) 这一帧算不算「慢帧」（isSlowFrame）
 *   2) 当前应该处于哪个性能档位（FrameQualityMonitor，只降不升防振荡）
 *   3) 页面隐藏 / 恢复时如何暂停与恢复（createVisibilityController）
 * 档位 → 具体视觉参数（粒子密度、缓冲分辨率、目标帧率）的映射由各消费方自定，
 * 本模块不耦合任何渲染概念。全部纯逻辑，不碰 DOM（visibility 除外），便于单测。
 *
 * 判定口径（与计划一致）：连续低于 45fps → 降一档；档位只降不升，
 * 避免帧率在阈值附近抖动造成画面忽快忽慢。
 * ============================================================ */

/** 性能档位：0 = 全速档（默认视觉，用户确认的浓密度保持该档），1/2 = 逐级降档 */
export type PerfTier = 0 | 1 | 2
export const MAX_TIER: PerfTier = 2

/** 档位判定配置（默认值经 2026-09-18 计划口径校准） */
export interface PerfTierConfig {
  /** 滚动统计窗口：最近 N 帧为一组参与判定 */
  windowSize: number
  /** 帧时长 ≥ 该值（ms）记为慢帧；45fps ≈ 22.2ms，取 23ms 留调度余量 */
  slowFrameMs: number
  /** 窗口内慢帧占比 ≥ 该值 → 降一档 */
  downgradeRatio: number
  /** 降档冷却帧数：刚降档后多少帧内不再判定，避免连续跳档 */
  cooldownFrames: number
  /** 异常帧阈值：帧间隔超过该值（ms）视为切后台/断点等异常，不计入窗口 */
  anomalyFrameMs: number
}

export const DEFAULT_PERF_CONFIG: Readonly<PerfTierConfig> = {
  windowSize: 60,
  slowFrameMs: 23,
  downgradeRatio: 0.5,
  cooldownFrames: 60,
  anomalyFrameMs: 250,
}

/** 单帧分类：ok 正常 / slow 慢帧 / ignore 异常帧或非法值（不进统计窗口） */
export type FrameClass = 'ok' | 'slow' | 'ignore'

export function classifyFrame(dtMs: number, config: PerfTierConfig = DEFAULT_PERF_CONFIG): FrameClass {
  if (!Number.isFinite(dtMs) || dtMs <= 0 || dtMs >= config.anomalyFrameMs) return 'ignore'
  return dtMs >= config.slowFrameMs ? 'slow' : 'ok'
}

/** 监测一帧并返回当前档位决策。 */
export interface TierDecision {
  /** 当前生效档位 */
  tier: PerfTier
  /** 本次推送是否触发了降档（消费方据此重算密度/缓冲等参数） */
  changed: boolean
}

/**
 * 帧质量监测器：滚动窗口统计慢帧占比，占比超阈值即降一档（只降不升）。
 * 用法：渲染循环每帧 `monitor.push(dtMs)`，`decision.changed` 为 true 时重算视觉参数。
 */
export class FrameQualityMonitor {
  tier: PerfTier = 0
  private readonly config: PerfTierConfig
  private readonly window: FrameClass[] = []
  private cooldown = 0

  constructor(config: PerfTierConfig = DEFAULT_PERF_CONFIG) {
    this.config = config
  }

  push(dtMs: number): TierDecision {
    const cls = classifyFrame(dtMs, this.config)
    if (cls === 'ignore') return { tier: this.tier, changed: false }

    this.window.push(cls)
    while (this.window.length > this.config.windowSize) this.window.shift()

    if (this.cooldown > 0) {
      this.cooldown--
      return { tier: this.tier, changed: false }
    }
    if (this.window.length < this.config.windowSize) {
      return { tier: this.tier, changed: false } // 窗口未满不判定，避免启动阶段误降
    }

    const slow = this.window.filter((c) => c === 'slow').length
    if (slow / this.config.windowSize >= this.config.downgradeRatio && this.tier < MAX_TIER) {
      this.tier = (this.tier + 1) as PerfTier
      this.cooldown = this.config.cooldownFrames
      this.window.length = 0 // 降档后重开窗口，按新档位重新观察
      return { tier: this.tier, changed: true }
    }
    return { tier: this.tier, changed: false }
  }

  /** 重置监测状态（组件重建 / 手动恢复全速档时用） */
  reset(tier: PerfTier = 0): void {
    this.tier = tier
    this.window.length = 0
    this.cooldown = 0
  }
}

/**
 * 页面可见性暂停 / 恢复控制器：document.visibilitychange 驱动，
 * 供 rAF 循环（星空）与定时帧循环（墨晕）统一挂接。返回 stop() 解除监听。
 */
export function createVisibilityController(onPause: () => void, onResume: () => void): { stop: () => void } {
  if (typeof document === 'undefined') return { stop: () => {} }
  const handler = () => {
    if (document.hidden) onPause()
    else onResume()
  }
  document.addEventListener('visibilitychange', handler)
  // 挂载时页面已处于隐藏态（如后台标签页预渲染）也要先暂停
  if (document.hidden) onPause()
  return { stop: () => document.removeEventListener('visibilitychange', handler) }
}
