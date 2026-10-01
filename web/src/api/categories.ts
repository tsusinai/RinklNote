import { api } from './http'
import type { Category } from '../types'

// server CategoryDTO wire 用 `billType` 表收支方向；此处映射为 `type`（App 语义），贯穿组件用 `type`。
export function normalizeCategory(c: Category & { billType?: string }): Category {
  return { ...c, type: (c.type ?? (c as any).billType as never) }
}

export const categories = {
  // opts 透传 allow401（同步路径 loadData 用）：401 变成 HttpError 交给状态卡展示，
  // 而不是在 api() 内部被清 token + 硬跳转劫持（体验审查 P2）。
  list: async (opts?: { allow401?: boolean }): Promise<Category[]> =>
    (await api<Array<Category & { billType: string }>>('/api/bills/categories', opts)).map(normalizeCategory),
}
