import { api } from './http'
import type { Bill, SyncResponse, BillSearchResponse, MoneyStyle } from '../types'

export interface CreateBillPayload {
  amountMinor: number; billType: MoneyStyle; categoryId: number; categoryName: string;
  subCategoryName?: string | null; accountId: number; remark?: string | null; date?: number
}

/** Forward-paginate all bills using the server's composite cursor (updatedAt, id).
 *  Ported 1:1 from web/index.html fetchAllBills() — keep behavior identical. */
export async function fetchAllBills(): Promise<Bill[]> {
  const all: Bill[] = []
  let after = 0, afterId: number | null = null, hasMore = true, guard = 0
  while (hasMore && guard++ < 500) {
    const query: Record<string, number | undefined> = { limit: 200 }
    if (after > 0) query.after = after
    if (afterId != null) query.afterId = afterId
    const res = await api<SyncResponse>('/api/bills/sync', { query })
    const list = res.bills || []
    if (list.length) all.push(...list)
    after = res.nextAfter != null ? res.nextAfter : Math.max(0, ...list.map(b => b.updatedAt || 0))
    afterId = res.nextAfterId != null ? res.nextAfterId : null
    hasMore = !!res.hasMore && list.length === 200
  }
  return all
}

export const bills = {
  create: (payload: CreateBillPayload) =>
    api<Bill>('/api/bills', { method: 'POST', body: payload }),
  update: (id: number, payload: Partial<CreateBillPayload> & { baseUpdatedAt?: number }) =>
    api<Bill>(`/api/bills/${id}`, { method: 'PUT', body: payload }),
  remove: (id: number) =>
    api<{ message: string }>(`/api/bills/${id}`, { method: 'DELETE' }),
  parse: (text: string) =>
    api<{ message?: string; bill?: Bill } | { message: string }>('/api/bills/parse', { method: 'POST', body: { text } }),
  // 服务端搜索（2026-09-18 Task 0.6）：q 备注/分类模糊；min/max 为「元」入参（服务端换整数分）；
  // from/to 为 yyyy-MM-dd（业务时区当天边界，含首尾）；page 从 1 起；pageSize 默认 20、上限 200。
  search: (params: {
    q?: string; min?: number; max?: number; categoryId?: number;
    from?: string; to?: string; page?: number; pageSize?: number
  }) => api<BillSearchResponse>('/api/bills/search', { query: params }),
}
