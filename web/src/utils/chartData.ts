import type { Bill } from '../types'

export type ChartPeriod = 'week' | 'month' | 'year'

// 说明：本文件所有聚合结果均为**整数分**（minor unit），不做元的换算与四舍五入。
// 展示层（ECharts tooltip / axis）统一用 utils/money.ts 的 formatMoney 转换。

export function inPeriod(bill: Bill, period: ChartPeriod, now = Date.now()): boolean {
  const d = new Date(bill.date).getTime()
  if (period === 'week') return d >= now - 7 * 86400000 && d <= now
  if (period === 'year') return d >= now - 365 * 86400000 && d <= now
  const n = new Date(now)
  const start = new Date(n.getFullYear(), n.getMonth(), 1).getTime()
  return d >= start && d <= now
}

// 日常支出柱状：以今天午夜为锚，period 决定桶数
export function dailyExpense(bills: Bill[], period: ChartPeriod, now = Date.now()): { name: string; value: number }[] {
  const n = new Date(now)
  const dayCount = period === 'week' ? 7 : period === 'year' ? 365 : n.getDate()
  const buckets: Record<string, number> = {}
  const order: string[] = []
  const first = new Date(n.getFullYear(), n.getMonth(), n.getDate() - (dayCount - 1)).getTime()
  for (const b of bills) {
    if (b.billType !== 'EXPENSE') continue
    const t = b.date
    if (t < first || t > now) continue
    const key = new Date(t).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
    if (!(key in buckets)) { buckets[key] = 0; order.push(key) }
    buckets[key] += b.amountMinor
  }
  return order.map((name) => ({ name, value: buckets[name] ?? 0 }))
}

// 月度趋势：全部账单，按 YYYY年M月 分组，取最近 12 月
export function monthlyTrend(bills: Bill[], now = Date.now()): { name: string; expense: number; income: number }[] {
  const buckets: Record<string, { expense: number; income: number; sortKey: number }> = {}
  const monthMs = 30 * 86400000
  for (const b of bills) {
    if (b.date < now - 12 * monthMs) continue
    const d = new Date(b.date)
    const key = d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'short' })
    const sortKey = d.getFullYear() * 12 + d.getMonth()
    if (!buckets[key]) buckets[key] = { expense: 0, income: 0, sortKey }
    if (b.billType === 'EXPENSE') buckets[key].expense += b.amountMinor
    else buckets[key].income += b.amountMinor
  }
  return Object.entries(buckets)
    .sort((a, b) => a[1].sortKey - b[1].sortKey)
    .map(([name, v]) => ({ name, expense: v.expense, income: v.income }))
}

// 支出分类饼图
export function expenseByCategory(bills: Bill[], period: ChartPeriod, now = Date.now()): { name: string; value: number }[] {
  const m: Record<string, number> = {}
  for (const b of bills) {
    if (b.billType !== 'EXPENSE' || !inPeriod(b, period, now)) continue
    m[b.categoryName] = (m[b.categoryName] ?? 0) + b.amountMinor
  }
  return Object.entries(m).map(([name, value]) => ({ name, value }))
}
