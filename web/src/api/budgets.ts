import { api } from './http'
import type { Budget } from '../types'

export const budgets = {
  list: () => api<Budget[]>('/api/budgets'),
  upsert: (monthStart: number, amountMinor: number) =>
    api<Budget>('/api/budgets', { method: 'PUT', body: { monthStart, amountMinor } }),
}
