import { api } from './http'
import type { QqBotStatus, QqBotBindStatus, MessageResponse } from '../types'

export const qqBot = {
  status: () => api<QqBotStatus>('/api/qq-bot/status'),
  bindStatus: () => api<QqBotBindStatus>('/api/qq-bot/bind-status'),
  saveConfig: (appId: string, clientSecret: string) =>
    api<MessageResponse>('/api/qq-bot/config', { method: 'POST', body: { appId, clientSecret } }),
  bind: (code: string) => api<MessageResponse>('/api/qq-bot/bind', { method: 'POST', body: { code } }),
  unbind: () => api<MessageResponse>('/api/qq-bot/unbind', { method: 'POST' }),
}
