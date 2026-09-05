export type DemoKind = 'EXPENSE' | 'INCOME'

export interface DemoBill {
  id: number
  amount: number
  category: string
  subCategory?: string
  kind: DemoKind
  time: string
  remark?: string
}

export interface DemoDay {
  date: string   // epoch ms string
  label: string  // '今天' | '昨天' | '8月4日'
  total: number  // 当日支出合计（正向数字）
  bills: DemoBill[]
}

export const demoDays: DemoDay[] = [
  {
    date: '2026-08-05', label: '今天', total: 68.5,
    bills: [
      { id: 1, amount: 25, category: '三餐', kind: 'EXPENSE', time: '08:24', remark: '早餐 · 豆浆油条' },
      { id: 2, amount: 18.5, category: '交通', kind: 'EXPENSE', time: '09:10', remark: '地铁通勤' },
      { id: 3, amount: 25, category: '三餐', kind: 'EXPENSE', time: '12:40', remark: '午餐' },
    ],
  },
  {
    date: '2026-08-04', label: '昨天', total: 342,
    bills: [
      { id: 4, amount: 128, category: '购物', subCategory: '数码', kind: 'EXPENSE', time: '20:15', remark: '外接键盘' },
      { id: 5, amount: 88, category: '娱乐', kind: 'EXPENSE', time: '21:02', remark: '电影票' },
      { id: 6, amount: 126, category: '三餐', kind: 'EXPENSE', time: '12:30', remark: '周末聚餐' },
    ],
  },
  {
    date: '2026-08-03', label: '8月3日', total: 35.5,
    bills: [
      { id: 7, amount: 12.5, category: '三餐', kind: 'EXPENSE', time: '08:30' },
      { id: 8, amount: 15, category: '交通', kind: 'EXPENSE', time: '18:44' },
      { id: 9, amount: 8, category: '零食', kind: 'EXPENSE', time: '15:12' },
    ],
  },
]

export type DemoAccountKind = 'asset' | 'liability'

export interface DemoAccount {
  id: number
  name: string
  balance: number  // 期初 + 账单派生后的当前余额（正向数字）
  iconColor: string
  kind: DemoAccountKind
}

export const demoAccounts: DemoAccount[] = [
  { id: 1, name: '微信', balance: 2840.36, iconColor: '#28C145', kind: 'asset' },
  { id: 2, name: '支付宝', balance: 5230.14, iconColor: '#06B4FD', kind: 'asset' },
  { id: 3, name: '信用卡', balance: 1380.5, iconColor: '#EF4444', kind: 'liability' },
]

// 净资 = 资产 - 负债
export const demoNetAssets = 6690.0

export interface DemoBudgetCategory { name: string; amount: number; color: string }

export interface DemoBudget {
  monthLabel: string
  spent: number
  total: number
  byCategory: DemoBudgetCategory[]
}

export const demoBudget: DemoBudget = {
  monthLabel: '8月', spent: 3520, total: 5000,
  byCategory: [
    { name: '三餐', amount: 1450, color: '#7EC1FC' },
    { name: '交通', amount: 480, color: '#CA3032' },
    { name: '购物', amount: 920, color: '#04A433' },
    { name: '娱乐', amount: 670, color: '#F59E0B' },
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
