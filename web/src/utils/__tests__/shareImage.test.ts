import { describe, it, expect } from 'vitest'
import { buildMonthlyShareData } from '../shareImage'
import type { Bill } from '../../types'

/* 月度分享图聚合单测（Task 3.5）：口径必须与图表页（chartData.ts）一致——
 * 自然月窗口、整数分累加、当月不含未来账单、历史月取全月。 */

let seq = 0
function bill(p: Partial<Bill> & { date: number; amountMinor: number }): Bill {
  return {
    id: ++seq, billType: 'EXPENSE', categoryId: 1, categoryName: '餐饮', subCategoryName: null,
    accountId: 1, remark: null, source: 'WEB', createdAt: p.date, updatedAt: null, deleted: false,
    ...p,
  }
}

/** 2026-09-18 12:00 本地时间（当前测试的「现在」） */
const NOW = new Date(2026, 8, 18, 12, 0, 0).getTime()

describe('buildMonthlyShareData 月度聚合', () => {
  it('月总收支与结余：整数分累加', () => {
    const bills = [
      bill({ date: new Date(2026, 8, 3).getTime(), amountMinor: 2500 }),
      bill({ date: new Date(2026, 8, 10).getTime(), amountMinor: 1200 }),
      bill({ date: new Date(2026, 8, 15).getTime(), amountMinor: 8000, billType: 'INCOME' }),
    ]
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.monthLabel).toBe('2026年9月')
    expect(d.totalExpenseMinor).toBe(3700)
    expect(d.totalIncomeMinor).toBe(8000)
    expect(d.balanceMinor).toBe(4300)
  })
  it('分类占比降序 + 占比一位小数（50/30/20）', () => {
    const bills = [
      bill({ date: new Date(2026, 8, 1).getTime(), amountMinor: 5000, categoryName: '餐饮' }),
      bill({ date: new Date(2026, 8, 2).getTime(), amountMinor: 3000, categoryName: '交通' }),
      bill({ date: new Date(2026, 8, 3).getTime(), amountMinor: 2000, categoryName: '购物' }),
    ]
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.topCategories.map((c) => [c.name, c.pct])).toEqual([
      ['餐饮', 50], ['交通', 30], ['购物', 20],
    ])
    expect(d.topCategories[0].amountMinor).toBe(5000)
  })
  it('只取 Top5 分类', () => {
    const bills = ['A', 'B', 'C', 'D', 'E', 'F'].map((name, i) =>
      bill({ date: new Date(2026, 8, i + 1).getTime(), amountMinor: 1000 - i, categoryName: name }))
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.topCategories).toHaveLength(5)
    expect(d.topCategories[0].name).toBe('A')
  })
  it('当月窗口不含未来账单（与图表页「本月」同口径）', () => {
    const bills = [
      bill({ date: new Date(2026, 8, 10).getTime(), amountMinor: 1000 }),
      bill({ date: new Date(2026, 8, 20).getTime(), amountMinor: 9999 }), // NOW 之后
    ]
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.totalExpenseMinor).toBe(1000)
  })
  it('历史月取全月：上月末尾账单计入，本月账单不计入', () => {
    const bills = [
      bill({ date: new Date(2026, 7, 31).getTime(), amountMinor: 1500 }), // 8月31日
      bill({ date: new Date(2026, 8, 1).getTime(), amountMinor: 2000 }),  // 9月1日
    ]
    const d = buildMonthlyShareData(bills, new Date(2026, 7, 15).getTime(), NOW)
    expect(d.monthLabel).toBe('2026年8月')
    expect(d.totalExpenseMinor).toBe(1500)
  })
  it('逐日支出数组：当月生成到「今天」为止，分桶正确（历史月才是全月）', () => {
    const bills = [
      bill({ date: new Date(2026, 8, 5, 10).getTime(), amountMinor: 300 }),
      bill({ date: new Date(2026, 8, 5, 18).getTime(), amountMinor: 200 }),
      bill({ date: new Date(2026, 8, 6).getTime(), amountMinor: 50 }),
    ]
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.dailyExpenseMinor).toHaveLength(18) // 当月：1 日 ~ NOW(18 日)
    expect(d.dailyExpenseMinor[4]).toBe(500) // 5 日两笔合计
    expect(d.dailyExpenseMinor[5]).toBe(50)  // 6 日
    // 历史月：全月 31 天（8 月）
    const d2 = buildMonthlyShareData(bills, new Date(2026, 7, 15).getTime(), NOW)
    expect(d2.dailyExpenseMinor).toHaveLength(31)
  })
  it('收入-only 月份：分类占比为空数组', () => {
    const bills = [bill({ date: new Date(2026, 8, 2).getTime(), amountMinor: 6000, billType: 'INCOME' })]
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.topCategories).toEqual([])
    expect(d.balanceMinor).toBe(6000)
  })
  it('软删除墓碑不计入聚合（同步协议会把 deleted=true 下发到客户端缓存）', () => {
    const bills = [
      bill({ date: new Date(2026, 8, 3).getTime(), amountMinor: 2500 }),
      bill({ date: new Date(2026, 8, 10).getTime(), amountMinor: 1200, deleted: true }),
      bill({ date: new Date(2026, 8, 10).getTime(), amountMinor: 1200, deleted: true, billType: 'INCOME' }),
    ]
    const d = buildMonthlyShareData(bills, NOW, NOW)
    expect(d.totalExpenseMinor).toBe(2500) // 墓碑不计入总支出
    expect(d.totalIncomeMinor).toBe(0)
    expect(d.topCategories).toHaveLength(1)
    expect(d.topCategories[0].pct).toBe(100)
  })
})
