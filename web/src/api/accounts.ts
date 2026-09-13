import { api } from './http'
import type { Account, MessageResponse } from '../types'

export const accounts = {
  list: () => api<Account[]>('/api/accounts'),
  create: (name: string, iconColor: string, balanceMinor = 0, iconKey = 'WALLET') =>
    api<Account>('/api/accounts', { method: 'POST', body: { name, iconColor, balanceMinor, iconKey } }),
  update: (id: number, p: { name?: string; iconColor?: string; balanceMinor?: number; iconKey?: string } | undefined) =>
    api<Account>(`/api/accounts/${id}`, { method: 'PUT', body: p ?? {} }),
  remove: (id: number) =>
    api<MessageResponse>(`/api/accounts/${id}`, { method: 'DELETE' }),
}
