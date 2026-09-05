import { api } from './http'
import type { Keyword } from '../types'

export const keywords = {
  list: () => api<Keyword[]>('/api/keywords'),
  create: (keyword: string, categoryName: string) =>
    api<{ id?: number; message?: string }>('/api/keywords', { method: 'POST', body: { keyword, categoryName } }),
  remove: (id: number) => api<{ message: string }>(`/api/keywords/${id}`, { method: 'DELETE' }),
}
