/* ============================================================
 * 预算烧穿风险预警（Task 4.1 Web 侧，纯函数本地派生、零存储）
 * 口径（与 App 端钉死对齐）：
 *   pct = 按当前燃烧速度预测到周期末的累计消耗 ÷ 周期预算 × 100
 *   - Web 预算周期 = 自然月（Budget.monthStart，本地口径）
 *   - 燃烧速度 = 已发生支出 ÷ 已过天数（含今天，月初至少 1 天）
 *   档位：<85 低风险；85~100 中风险（含边界 85 与 100）；>100 高风险
 * 全部实时派生自 bills 聚合，不落任何存储（与挑战系统「零存储」原则一致）。
 * ============================================================ */

import type { Bill } from '../types'
import { monthStart } from './date'

export type RiskLevel = 'low' | 'mid' | 'high'

/** 档位阈值（钉死）：预测占比 ≥85 进入中风险，>100 高风险 */
export const RISK_MID_THRESHOLD = 85
export const RISK_HIGH_THRESHOLD = 100

/** 档位判定：边界值归属 —— 恰好 85 与 100 都算中风险，超过 100 才是高风险 */
export function riskLevel(pct: number): RiskLevel {
  if (pct > RISK_HIGH_THRESHOLD) return 'high'
  if (pct >= RISK_MID_THRESHOLD) return 'mid'
  return 'low'
}

/** 当月天数（本地口径） */
export function daysInMonth(now: number): number {
  const d = new Date(now)
  return new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
}

/** 已过天数（含今天；月初至少 1，防除零） */
export function daysElapsed(now: number): number {
  return Math.max(1, new Date(now).getDate())
}

/**
 * 燃烧速度外推：按「日均消耗 × 当月天数」预测周期末累计消耗（整数分，四舍五入）。
 * 月末最后一天外推自然收敛为「已发生支出」本身。
 */
export function predictBurnMinor(expenseSoFarMinor: number, now: number): number {
  const predicted = (expenseSoFarMinor / daysElapsed(now)) * daysInMonth(now)
  return Math.round(predicted)
}

/** 预测占比：predicted ÷ budget × 100（调用方保证 budgetMinor > 0） */
export function riskPct(predictedMinor: number, budgetMinor: number): number {
  return (predictedMinor / budgetMinor) * 100
}

/** 横幅展示取整：与 App BurnRiskBar 的 `pct.toInt()` 同为截断（floor）。
 * 不能用 toFixed(0)——它会四舍五入（99.6% → 100%），与 App 同场景显示不一致。 */
export function displayPct(pct: number): number {
  return Math.floor(pct)
}

export interface BudgetRiskAssessment {
  level: RiskLevel
  pct: number
  predictedMinor: number
  expenseSoFarMinor: number
  budgetMinor: number
}

/** 一步到位：预算 + 已发生支出 + 当前时刻 → 风险评估 */
export function assessBudgetRisk(budgetMinor: number, expenseSoFarMinor: number, now: number): BudgetRiskAssessment {
  const predictedMinor = predictBurnMinor(expenseSoFarMinor, now)
  const pct = riskPct(predictedMinor, budgetMinor)
  return { level: riskLevel(pct), pct, predictedMinor, expenseSoFarMinor, budgetMinor }
}

/** 本月已发生支出（整数分；口径与图表页一致：本地自然月、不含未来账单） */
export function monthExpenseSoFar(bills: Bill[], now = Date.now()): number {
  const start = monthStart(now)
  let sum = 0
  for (const b of bills) {
    if (b.billType !== 'EXPENSE' || b.deleted || b.date < start || b.date > now) continue
    sum += b.amountMinor
  }
  return sum
}
