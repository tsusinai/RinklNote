import { api } from './http'
import type { AuthResponse, MeResponse, MessageResponse } from '../types'

// 登录身份（2026-09-17 优化登录方式）：手机号与邮箱同级。email 模式下 phone 传空串、
// email 非空，服务端（AuthRoutes.kt）据此选择身份校验；只传 phone 的旧语义不变。
export const auth = {
  login: (phone: string, password: string, email?: string) =>
    api<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: email ? { phone: '', email, password } : { phone, password },
    }),
  register: (phone: string, password: string, email?: string) =>
    api<AuthResponse>('/api/auth/register', {
      method: 'POST',
      body: email ? { phone: '', email, password } : { phone, password },
    }),
  me: () => api<MeResponse>('/api/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    api<MessageResponse>('/api/auth/password', { method: 'POST', body: { oldPassword, newPassword }, allow401: true }),
  setAiDisabled: (disabled: boolean) =>
    api<MessageResponse>('/api/auth/ai', { method: 'PUT', body: { disabled } }),
  aiStatus: () => api<{ disabled: boolean }>('/api/auth/ai'),
}
// 说明：旧 bindQq / unbindQq（/api/auth/bind-qq、/api/auth/unbind-qq）已随服务端下线删除；
// 机器人绑定一律走 /api/{qq,feishu,wecom}-bot/{bind,unbind,bind-status}（设置页机器人管理区）。
// 登录页 QQ 登录码入口（2026-09-17）随「优化登录方式」从新 UI 移除；服务端 /api/auth/qq-login
// 按仓库惯例（旧协议废弃但保留运行）继续可用，存量旧版客户端仍在用。
