import { api } from './http'
import type { Category } from '../types'

// server CategoryDTO wire 用 `billType` 表收支方向；此处映射为 `type`（App 语义），贯穿组件用 `type`。
export function normalizeCategory(c: Category & { billType?: string }): Category {
  return { ...c, type: (c.type ?? (c as any).billType as never) }
}

export const categories = {
  list: async (): Promise<Category[]> =>
    (await api<Array<Category & { billType: string }>>('/api/bills/categories')).map(normalizeCategory),
}
