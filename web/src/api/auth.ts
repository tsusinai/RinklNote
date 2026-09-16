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
}
// 说明：旧 bindQq / unbindQq（/api/auth/bind-qq、/api/auth/unbind-qq）已随服务端下线删除；
// 机器人绑定一律走 /api/{qq,feishu,wecom}-bot/{bind,unbind,bind-status}（设置页机器人管理区）。
