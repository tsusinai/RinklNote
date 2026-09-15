import type { Bill } from '../types'

export type ChartPeriod = 'week' | 'month' | 'year'

// 说明：本文件所有聚合结果均为**整数分**（minor unit），不做元的换算与四舍五入。
// 展示层（ECharts tooltip / axis）统一用 utils/money.ts 的 formatMoney 转换。
//
// 周期口径（硬伤修复：原「30 天 ≈ 1 月」滚动窗口造成的月漂移已废弃，全部改自然周期）：
//   - week  ：本周一 0 点 ~ 现在（按钮文案「本周」）
//   - month ：本月 1 日 0 点 ~ 现在（次月 1 日 0 点前均算当月，按钮文案「本月」）
//   - year  ：本年 1 月 1 日 0 点 ~ 现在（按钮文案「本年」）
// 日界一律用本地日期字段（new Date(y, m, d) 本地构造）推算，与后端 Asia/Shanghai
// 的「本地日期字符串」口径一致；禁止用 toISOString 等 UTC 口径（UTC+8 在早上 8 点前
// 会被换算成前一天，产生跨天漂移）。

/** 周期起点（本地口径的自然周期开始时刻） */
export function periodStart(period: ChartPeriod, now = Date.now()): number {
  const n = new Date(now)
  if (period === 'week') {
    // 周一为一周起点：getDay() 周日=0，换算成「距周一的天数」后回退
    const daysSinceMonday = (n.getDay() + 6) % 7
    return new Date(n.getFullYear(), n.getMonth(), n.getDate() - daysSinceMonday).getTime()
  }
  if (period === 'year') return new Date(n.getFullYear(), 0, 1).getTime()
  return new Date(n.getFullYear(), n.getMonth(), 1).getTime()
}

export function inPeriod(bill: Bill, period: ChartPeriod, now = Date.now()): boolean {
  return bill.date >= periodStart(period, now) && bill.date <= now
}

// 本地日期桶键：由日期字段拼「年-月-日」，仅作分桶/对位用，不做任何时区换算
function localDayKey(d: Date): string {
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

// 日常支出柱状：period 起点至今逐日分桶，无账单的日期补 0，按时间升序输出
export function dailyExpense(bills: Bill[], period: ChartPeriod, now = Date.now()): { name: string; value: number }[] {
  const start = periodStart(period, now)
  // 预生成 start→now 的逐日桶（含两端），保证横轴连续、无账单日也有 0 值柱
  const keys: string[] = []
  const labels: string[] = []
  const totals: Record<string, number> = {}
  const cursor = new Date(start)
  // 防御上限：year 口径最长 366 天，留余量防脏数据死循环
  let guard = 0
  while (cursor.getTime() <= now && guard < 400) {
    const key = localDayKey(cursor)
    keys.push(key)
    labels.push(`${cursor.getMonth() + 1}月${cursor.getDate()}日`)
    totals[key] = 0
    cursor.setDate(cursor.getDate() + 1)
    guard++
  }
  for (const b of bills) {
    if (b.billType !== 'EXPENSE' || b.date < start || b.date > now) continue
    const key = localDayKey(new Date(b.date))
    if (key in totals) totals[key] += b.amountMinor
  }
  return keys.map((key, i) => ({ name: labels[i], value: totals[key] }))
}

// 月度趋势：自然月口径 —— 窗口 = 含当月在内的最近 12 个自然月（自 11 个月前的
// 当月 1 日 0 点起），按本地年月分桶；无账单的月份补 0，按时间升序输出。
export function monthlyTrend(bills: Bill[], now = Date.now()): { name: string; expense: number; income: number }[] {
  const n = new Date(now)
  const start = new Date(n.getFullYear(), n.getMonth() - 11, 1).getTime()
  const buckets: { name: string; expense: number; income: number }[] = []
  const indexOf: Record<string, number> = {}
  // 游标固定从「11 个月前的当月 1 日」起逐月 +1：日号恒为 1，setMonth 无大小月溢出问题
  const cursor = new Date(n.getFullYear(), n.getMonth() - 11, 1)
  for (let i = 0; i < 12; i++) {
    indexOf[`${cursor.getFullYear()}-${cursor.getMonth()}`] = i
    buckets.push({ name: `${cursor.getFullYear()}年${cursor.getMonth() + 1}月`, expense: 0, income: 0 })
    cursor.setMonth(cursor.getMonth() + 1)
  }
  for (const b of bills) {
    if (b.date < start || b.date > now) continue
    const d = new Date(b.date)
    const i = indexOf[`${d.getFullYear()}-${d.getMonth()}`]
    if (i === undefined) continue
    if (b.billType === 'EXPENSE') buckets[i].expense += b.amountMinor
    else buckets[i].income += b.amountMinor
  }
  return buckets
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
