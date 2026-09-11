import { api } from './http'
import type { Account, MessageResponse } from '../types'

export const accounts = {
  list: () => api<Account[]>('/api/accounts'),
  create: (name: string, iconColor: string, balanceMinor = 0) =>
    api<Account>('/api/accounts', { method: 'POST', body: { name, iconColor, balanceMinor } }),
  update: (id: number, p: { name?: string; iconColor?: string; balanceMinor?: number } | undefined) =>
    api<Account>(`/api/accounts/${id}`, { method: 'PUT', body: p ?? {} }),
  remove: (id: number) =>
    api<MessageResponse>(`/api/accounts/${id}`, { method: 'DELETE' }),
}
