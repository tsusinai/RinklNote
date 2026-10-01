import { api } from './http'
import type { Account, MessageResponse } from '../types'

export const accounts = {
  // opts 透传 allow401（同步路径用）：401 交给调用方展示，不在 api() 内部硬跳转。
  list: (opts?: { allow401?: boolean }) => api<Account[]>('/api/accounts', opts),
  create: (name: string, iconColor: string, balanceMinor = 0, iconKey = 'WALLET') =>
    api<Account>('/api/accounts', { method: 'POST', body: { name, iconColor, balanceMinor, iconKey } }),
  update: (id: number, p: { name?: string; iconColor?: string; balanceMinor?: number; iconKey?: string } | undefined) =>
    api<Account>(`/api/accounts/${id}`, { method: 'PUT', body: p ?? {} }),
  remove: (id: number) =>
    api<MessageResponse>(`/api/accounts/${id}`, { method: 'DELETE' }),
}
