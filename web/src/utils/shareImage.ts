/* ============================================================
 * 月度账单分享图导出（Task 3.5，零依赖手写 Canvas）
 * - 聚合口径完全复用 utils/chartData.ts：自然月（本地日期）、整数分、
 *   支出分类占比 / 每日支出分桶直接调用既有函数（传 effectiveNow 控制月份截断）。
 * - 配色全部读 theme.css 的 CSS 变量（getComputedStyle），暗色模式下导出同样成立。
 * - 结构：buildMonthlyShareData（纯聚合，可单测）→ drawShareImage（绘制）→
 *   exportMonthlyShareImage（建画布 + 触发 PNG 下载）。
 * ============================================================ */

import type { Bill } from '../types'
import { dailyExpense, expenseByCategory, periodStart } from './chartData'
import { readChartPalette, readExpenseIncomeColors } from './echartsTheme'
import { nextMonthStart } from './date'

export interface MonthlyShareCategory {
  name: string
  amountMinor: number
  /** 占当月总支出的百分比（0~100，一位小数） */
  pct: number
}

export interface MonthlyShareData {
  /** 形如「2026年9月」 */
  monthLabel: string
  totalExpenseMinor: number
  totalIncomeMinor: number
  balanceMinor: number
  /** 支出分类按金额降序，最多 5 条 */
  topCategories: MonthlyShareCategory[]
  /** 逐日支出（整数分），长度 = 当月天数，与热力条对位 */
  dailyExpenseMinor: number[]
}

/**
 * 月度聚合（口径与图表页一致）：
 * - 窗口 = monthTs 所在自然月 1 日 0 点起，至 min(now, 次月 1 日 0 点前)；
 *   当前月导出与图表页「本月」完全同口径（不含未来账单），历史月则取全月。
 * - 金额一律整数分累加，无浮点参与。
 */
export function buildMonthlyShareData(bills: Bill[], monthTs: number, now = Date.now()): MonthlyShareData {
  const d = new Date(monthTs)
  const monthStart = periodStart('month', monthTs)
  const effectiveNow = Math.min(now, nextMonthStart(monthTs) - 1)

  let totalExpenseMinor = 0
  let totalIncomeMinor = 0
  for (const b of bills) {
    if (b.date < monthStart || b.date > effectiveNow) continue
    if (b.billType === 'EXPENSE') totalExpenseMinor += b.amountMinor
    else if (b.billType === 'INCOME') totalIncomeMinor += b.amountMinor
  }

  // 分类占比：复用图表页的 expenseByCategory（同为整数分聚合）
  const cats = expenseByCategory(bills, 'month', effectiveNow)
    .sort((a, b) => b.value - a.value)
    .slice(0, 5)
    .map((c) => ({
      name: c.name,
      amountMinor: c.value,
      pct: totalExpenseMinor ? Math.round((c.value / totalExpenseMinor) * 1000) / 10 : 0,
    }))

  // 逐日支出：复用 dailyExpense 的逐日分桶（含无账单日补 0）
  const daily = dailyExpense(bills, 'month', effectiveNow)

  return {
    monthLabel: `${d.getFullYear()}年${d.getMonth() + 1}月`,
    totalExpenseMinor,
    totalIncomeMinor,
    balanceMinor: totalIncomeMinor - totalExpenseMinor,
    topCategories: cats,
    dailyExpenseMinor: daily.map((x) => x.value),
  }
}

/* ── 绘制 ── */

export interface SharePalette {
  bg: string; card: string; text: string; muted: string
  border: string; primary: string
  expense: string; income: string
  palette: string[]
}

function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined' || !document.documentElement) return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 运行时取当前主题配色（导出那一刻的亮/暗状态） */
export function readSharePalette(): SharePalette {
  const { expense, income } = readExpenseIncomeColors()
  return {
    bg: cssVar('--bg', '#F7F7F9'),
    card: cssVar('--card', '#FFFFFF'),
    text: cssVar('--text', '#1f2937'),
    muted: cssVar('--muted', '#6b7280'),
    border: cssVar('--border-light', '#f3f4f6'),
    primary: cssVar('--primary', '#7EC1FC'),
    expense,
    income,
    palette: readChartPalette(),
  }
}

/** 分→「1,234.50」展示串（带千分位与固定 2 位小数，纯整数拆分） */
function fmtYuan(minor: number): string {
  const n = Math.round(minor)
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(n)
  const yuan = Math.floor(abs / 100)
  const withSep = String(yuan).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${sign}${withSep}.${String(abs % 100).padStart(2, '0')}`
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  const rr = Math.min(r, w / 2, h / 2)
  ctx.beginPath()
  ctx.moveTo(x + rr, y)
  ctx.arcTo(x + w, y, x + w, y + h, rr)
  ctx.arcTo(x + w, y + h, x, y + h, rr)
  ctx.arcTo(x, y + h, x, y, rr)
  ctx.arcTo(x, y, x + w, y, rr)
  ctx.closePath()
}

const SANS = '"Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif'
const SERIF = '"Fraunces", "Noto Serif SC", Georgia, serif'

/** 把分享图绘制到画布上（逻辑尺寸 750×高按内容，2 倍物理像素保清晰）。 */
export function drawShareImage(canvas: HTMLCanvasElement, data: MonthlyShareData, pal: SharePalette): void {
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const W = 750
  const pad = 48
  const catsH = data.topCategories.length * 52
  const days = data.dailyExpenseMinor.length || 1
  const H = 420 + catsH + 170

  const scale = 2
  canvas.width = W * scale
  canvas.height = H * scale
  ctx.scale(scale, scale)

  // 底：页面背景打底，卡片圆角纸面浮起
  ctx.fillStyle = pal.bg
  ctx.fillRect(0, 0, W, H)
  roundRect(ctx, 16, 16, W - 32, H - 32, 24)
  ctx.fillStyle = pal.card
  ctx.fill()

  // 头部：月份 + 品牌徽记
  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = pal.primary
  roundRect(ctx, pad, 52, 8, 40, 4)
  ctx.fill()
  ctx.fillStyle = pal.text
  ctx.font = `bold 38px ${SERIF}`
  ctx.fillText(data.monthLabel, pad + 20, 88)
  ctx.fillStyle = pal.muted
  ctx.font = `14px ${SANS}`
  ctx.textAlign = 'right'
  ctx.fillText('记一笔 · 月度账单', W - pad, 66)
  ctx.textAlign = 'left'

  // 总收支三列
  const cols: { label: string; value: string; color: string }[] = [
    { label: '总支出', value: fmtYuan(-data.totalExpenseMinor), color: pal.expense },
    { label: '总收入', value: fmtYuan(data.totalIncomeMinor), color: pal.income },
    { label: '结余', value: fmtYuan(data.balanceMinor), color: pal.text },
  ]
  const colW = (W - pad * 2) / 3
  cols.forEach((c, i) => {
    const x = pad + colW * i
    ctx.fillStyle = pal.muted
    ctx.font = `14px ${SANS}`
    ctx.fillText(c.label, x, 168)
    ctx.fillStyle = c.color
    ctx.font = `bold 30px ${SANS}`
    ctx.fillText(c.value, x, 206)
  })
  // 分隔线
  ctx.strokeStyle = pal.border
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(pad, 244)
  ctx.lineTo(W - pad, 244)
  ctx.stroke()

  // 分类占比 Top5（横条 + 色板对位）
  ctx.fillStyle = pal.text
  ctx.font = `bold 17px ${SANS}`
  ctx.fillText('支出去向', pad, 284)
  const barMax = W - pad * 2
  data.topCategories.forEach((c, i) => {
    const y = 316 + i * 52
    ctx.fillStyle = pal.text
    ctx.font = `15px ${SANS}`
    ctx.fillText(c.name, pad, y)
    ctx.textAlign = 'right'
    ctx.font = `bold 15px ${SANS}`
    ctx.fillText(`¥${fmtYuan(c.amountMinor)}`, W - pad, y)
    ctx.textAlign = 'left'
    // 占比条
    roundRect(ctx, pad, y + 10, barMax, 10, 5)
    ctx.fillStyle = pal.border
    ctx.fill()
    const fillW = Math.max(barMax * (c.pct / 100), 10)
    roundRect(ctx, pad, y + 10, fillW, 10, 5)
    ctx.fillStyle = pal.palette[i % pal.palette.length]
    ctx.fill()
    ctx.fillStyle = pal.muted
    ctx.font = `12px ${SANS}`
    ctx.fillText(`${c.pct}%`, pad + fillW + 8, y + 20)
  })

  // 每日支出热力条
  const heatY = 316 + catsH + 36
  ctx.fillStyle = pal.text
  ctx.font = `bold 17px ${SANS}`
  ctx.fillText('每日支出热力', pad, heatY)
  const maxDay = Math.max(...data.dailyExpenseMinor, 1)
  const cellGap = 3
  const cellW = (barMax - cellGap * (days - 1)) / days
  const cellH = 26
  data.dailyExpenseMinor.forEach((v, i) => {
    const x = pad + i * (cellW + cellGap)
    const y = heatY + 14
    if (v <= 0) {
      roundRect(ctx, x, y, cellW, cellH, 4)
      ctx.fillStyle = pal.border
      ctx.fill()
      return
    }
    const a = 0.25 + 0.75 * (v / maxDay)
    roundRect(ctx, x, y, cellW, cellH, 4)
    ctx.fillStyle = hexWithAlpha(pal.expense, a)
    ctx.fill()
  })
  // 热力条刻度
  ctx.fillStyle = pal.muted
  ctx.font = `12px ${SANS}`
  ctx.fillText('1日', pad, heatY + 60)
  ctx.textAlign = 'right'
  ctx.fillText(`${days}日`, W - pad, heatY + 60)
  ctx.textAlign = 'left'

  // 页脚
  ctx.fillStyle = pal.muted
  ctx.font = `13px ${SANS}`
  ctx.fillText('RinklNote 记一笔 · rinklnote', pad, H - pad + 10)
}

/** #RRGGBB → rgba(r,g,b,a)；非 hex 色值原样返回（css var 回退时兜底不透明） */
function hexWithAlpha(hex: string, a: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return hex
  const n = parseInt(m[1], 16)
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a.toFixed(3)})`
}

/**
 * 导出月度分享图 PNG 并触发下载。
 * @returns 是否成功导出（无 Canvas 环境 / toBlob 失败返回 false，便于调用方提示）
 */
export function exportMonthlyShareImage(bills: Bill[], monthTs: number, now = Date.now()): Promise<boolean> {
  return new Promise((resolve) => {
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d')
    if (!ctx) { resolve(false); return }
    const data = buildMonthlyShareData(bills, monthTs, now)
    drawShareImage(canvas, data, readSharePalette())
    if (typeof canvas.toBlob !== 'function') { resolve(false); return }
    canvas.toBlob((blob) => {
      if (!blob) { resolve(false); return }
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      const d = new Date(monthTs)
      a.href = url
      a.download = `rinklnote-${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}.png`
      a.click()
      URL.revokeObjectURL(url)
      resolve(true)
    }, 'image/png')
  })
}
