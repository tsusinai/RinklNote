import type { Bill } from '../types'
import { fmtDateTime } from './date'
import { minorToDecimal } from './money'

export function csvSafe(v: string | number | null | undefined): string {
  const s = v == null ? '' : String(v)
  if (/^[=+\-@\t\r]/.test(s)) return "'" + s
  return s
}

export function toCsv(rows: (string | number | null | undefined)[][]): string {
  return rows.map((r) => r.map(csvSafe).join(',')).join('\r\n')
}

/** 账单 → CSV 行（首行为表头）：软删除墓碑剔除。
 *  服务端 /api/bills/sync 会把 deleted=true 的墓碑一并下发（同步协议需要），
 *  直接遍历客户端缓存全集导出会把已删除账单混进 CSV，此处统一过滤。 */
export function billCsvRows(bills: Bill[]): (string | number)[][] {
  const rows: (string | number)[][] = [['日期', '类型', '分类', '子分类', '金额', '备注', '来源']]
  for (const b of bills) {
    if (b.deleted) continue
    rows.push([
      fmtDateTime(b.date), b.billType === 'EXPENSE' ? '支出' : '收入', b.categoryName,
      b.subCategoryName || '', minorToDecimal(b.amountMinor), b.remark || '', b.source,
    ])
  }
  return rows
}
