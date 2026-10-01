import { api } from './http'
import type { Keyword } from '../types'

export const keywords = {
  // opts 透传 allow401（同步路径 loadData 用）：401 交给调用方展示，不在 api() 内部硬跳转。
  list: (opts?: { allow401?: boolean }) => api<Keyword[]>('/api/keywords', opts),
  create: (keyword: string, categoryName: string) =>
    api<{ id?: number; message?: string }>('/api/keywords', { method: 'POST', body: { keyword, categoryName } }),
  remove: (id: number) => api<{ message: string }>(`/api/keywords/${id}`, { method: 'DELETE' }),
}
