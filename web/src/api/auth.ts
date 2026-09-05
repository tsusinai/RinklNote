import { api } from './http'
import type { AuthResponse, MeResponse, MessageResponse } from '../types'

export const auth = {
  login: (phone: string, password: string) =>
    api<AuthResponse>('/api/auth/login', { method: 'POST', body: { phone, password } }),
  register: (phone: string, password: string) =>
    api<AuthResponse>('/api/auth/register', { method: 'POST', body: { phone, password } }),
  qqLogin: (code: string) =>
    api<AuthResponse>('/api/auth/qq-login', { method: 'POST', body: { code } }),
  me: () => api<MeResponse>('/api/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    api<MessageResponse>('/api/auth/password', { method: 'POST', body: { oldPassword, newPassword }, allow401: true }),
  setAiDisabled: (disabled: boolean) =>
    api<MessageResponse>('/api/auth/ai', { method: 'PUT', body: { disabled } }),
  aiStatus: () => api<{ disabled: boolean }>('/api/auth/ai'),
  bindQq: (qqNumber: string) =>
    api<MessageResponse>('/api/auth/bind-qq', { method: 'POST', body: { qqNumber } }),
  unbindQq: () => api<MessageResponse>('/api/auth/unbind-qq', { method: 'POST' }),
}
