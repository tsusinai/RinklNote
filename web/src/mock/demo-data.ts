export type DemoKind = 'EXPENSE' | 'INCOME'

export interface DemoBill {
  id: number
  amountMinor: number  // 金额（分）
  category: string
  subCategory?: string
  kind: DemoKind
  time: string
  remark?: string
}

export interface DemoDay {
  date: string   // epoch ms string
  label: string  // '今天' | '昨天' | '8月4日'
  totalMinor: number  // 当日支出合计（分，正向数字）
  bills: DemoBill[]
}

// 金额全部以「分」存储：元 × 100。例如 25 元 -> 2500 分。
export const demoDays: DemoDay[] = [
  {
    date: '2026-08-05', label: '今天', totalMinor: 6850, // 25 + 18.5 + 25 = 68.5 元
    bills: [
      { id: 1, amountMinor: 2500, category: '三餐', kind: 'EXPENSE', time: '08:24', remark: '早餐 · 豆浆油条' },
      { id: 2, amountMinor: 1850, category: '交通', kind: 'EXPENSE', time: '09:10', remark: '地铁通勤' },
      { id: 3, amountMinor: 2500, category: '三餐', kind: 'EXPENSE', time: '12:40', remark: '午餐' },
    ],
  },
  {
    date: '2026-08-04', label: '昨天', totalMinor: 34200, // 128 + 88 + 126 = 342 元
    bills: [
      { id: 4, amountMinor: 12800, category: '购物', subCategory: '数码', kind: 'EXPENSE', time: '20:15', remark: '外接键盘' },
      { id: 5, amountMinor: 8800, category: '娱乐', kind: 'EXPENSE', time: '21:02', remark: '电影票' },
      { id: 6, amountMinor: 12600, category: '三餐', kind: 'EXPENSE', time: '12:30', remark: '周末聚餐' },
    ],
  },
  {
    date: '2026-08-03', label: '8月3日', totalMinor: 3550, // 12.5 + 15 + 8 = 35.5 元
    bills: [
      { id: 7, amountMinor: 1250, category: '三餐', kind: 'EXPENSE', time: '08:30' },
      { id: 8, amountMinor: 1500, category: '交通', kind: 'EXPENSE', time: '18:44' },
      { id: 9, amountMinor: 800, category: '零食', kind: 'EXPENSE', time: '15:12' },
    ],
  },
]

export type DemoAccountKind = 'asset' | 'liability'

export interface DemoAccount {
  id: number
  name: string
  balanceMinor: number  // 期初 + 账单派生后的当前余额（分，正向数字）
  iconColor: string
  kind: DemoAccountKind
}

export const demoAccounts: DemoAccount[] = [
  { id: 1, name: '微信', balanceMinor: 284036, iconColor: '#28C145', kind: 'asset' },
  { id: 2, name: '支付宝', balanceMinor: 523014, iconColor: '#06B4FD', kind: 'asset' },
  { id: 3, name: '信用卡', balanceMinor: 138050, iconColor: '#EF4444', kind: 'liability' },
]

// 净资 = 资产(2840.36 + 5230.14) - 负债(1380.5) = 6690.0 元 -> 669000 分
export const demoNetAssets = 669000

export interface DemoBudgetCategory { name: string; amountMinor: number; color: string }

export interface DemoBudget {
  monthLabel: string
  spentMinor: number
  totalMinor: number
  byCategory: DemoBudgetCategory[]
}

export const demoBudget: DemoBudget = {
  monthLabel: '8月', spentMinor: 352000, totalMinor: 500000, // 3520 元 / 5000 元
  byCategory: [
    { name: '三餐', amountMinor: 145000, color: '#7EC1FC' },
    { name: '交通', amountMinor: 48000, color: '#CA3032' },
    { name: '购物', amountMinor: 92000, color: '#04A433' },
    { name: '娱乐', amountMinor: 67000, color: '#F59E0B' },
  ],
}

export interface DemoInsight {
  monthLabel: string
  summary: string
  highlights: string[]
}

export const demoInsight: DemoInsight = {
  monthLabel: '8月',
  summary: '本月共支出 ¥3,520，日均 ¥117。三餐占大头，娱乐略有回升；整体符合预算，继续保持。',
  highlights: [
    '三餐支出占 41%，比上月下降 6%',
    '交通支出较平稳，打车频次减少 2 次',
    '本月记账最活跃：连续 12 天打卡',
  ],
}
