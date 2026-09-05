import { api } from './http'
import type { Template } from '../types'

export const templates = {
  list: () => api<Template[]>('/api/templates'),
  create: (p: { label: string; amount: number; categoryId: number; categoryName: string; accountId: number }) =>
    api<{ id?: number; message?: string }>('/api/templates', { method: 'POST', body: { ...p, sortOrder: 0 } }),
  remove: (id: number) => api<{ message: string }>(`/api/templates/${id}`, { method: 'DELETE' }),
}
