import { api } from './http'
import type { Account, MessageResponse } from '../types'

export const accounts = {
  list: () => api<Account[]>('/api/accounts'),
  create: (name: string, iconColor: string, balance = 0) =>
    api<Account>('/api/accounts', { method: 'POST', body: { name, iconColor, balance } }),
  update: (id: number, p: { name?: string; iconColor?: string; balance?: number } | undefined) =>
    api<Account>(`/api/accounts/${id}`, { method: 'PUT', body: p ?? {} }),
  remove: (id: number) =>
    api<MessageResponse>(`/api/accounts/${id}`, { method: 'DELETE' }),
}
