import { describe, it, expect } from 'vitest'
import { csvSafe, toCsv, billCsvRows } from '../csv'
import type { Bill } from '../../types'

describe('csv', () => {
  it('prefixes formula-injection cells', () => {
    expect(csvSafe('=SUM(A1)')).toBe("'=SUM(A1)")
    expect(csvSafe('+1')).toBe("'+1")
    expect(csvSafe('hello')).toBe('hello')
  })
  it('joins rows with CRLF', () => {
    expect(toCsv([['a', 'b'], ['c', 'd']])).toBe('a,b\r\nc,d')
  })
})

/* billCsvRows（命令面板 / 账单页共用）：软删除墓碑必须剔除 ——
 * 服务端 /api/bills/sync 会把 deleted=true 的账单一并下发（同步协议需要墓碑），
 * 直接遍历缓存全集导出会把已删除账单混进 CSV（账单页服务端搜索导出无此问题）。 */
function bill(p: Partial<Bill>): Bill {
  return {
    id: p.id ?? 1, amountMinor: p.amountMinor ?? 2500, billType: p.billType ?? 'EXPENSE',
    categoryId: 1, categoryName: p.categoryName ?? '餐饮', subCategoryName: p.subCategoryName ?? null,
    accountId: 1, remark: p.remark ?? null, date: p.date ?? new Date(2026, 8, 18, 9, 30).getTime(),
    source: p.source ?? 'WEB', createdAt: 0, updatedAt: null, deleted: p.deleted ?? false,
  }
}

describe('billCsvRows 导出行构建', () => {
  it('表头 + 金额走 minorToDecimal 纯整数拆分', () => {
    const rows = billCsvRows([bill({ amountMinor: 1234567, remark: '午餐' })])
    expect(rows[0]).toEqual(['日期', '类型', '分类', '子分类', '金额', '备注', '来源'])
    expect(rows[1]).toContain('12345.67')
    expect(rows[1]).toContain('支出')
  })
  it('软删除账单不出现在导出行', () => {
    const rows = billCsvRows([
      bill({ id: 1, categoryName: '保留' }),
      bill({ id: 2, categoryName: '已删除', deleted: true }),
    ])
    expect(rows).toHaveLength(2) // 表头 + 仅 1 笔
    expect(JSON.stringify(rows)).not.toContain('已删除')
  })
  it('全部为软删除时只剩表头（调用方据此提示「暂无账单可导出」）', () => {
    const rows = billCsvRows([bill({ deleted: true }), bill({ deleted: true })])
    expect(rows).toHaveLength(1)
  })
})
