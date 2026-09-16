import { api } from './http'

/* ── 管理端只读 API（/api/admin，需管理员；字段与服务端 AdminService DTO 一一对应）──
 * 隐私契约：所有接口只含聚合计数与运维元数据；手机号已是掩码（138****1234）。
 * 非管理员访问返回 403 {"message":"需要管理员权限"}（见 plugins/Security.kt）。 */

export interface AdminOverview {
  totalUsers: number
  totalBills: number
  todayActiveUsers: number
  qqBoundUsers: number
  dbType: string
  llmConfigured: boolean
  asrConfigured: boolean
}

export interface AdminUserRow {
  id: number
  phone: string | null
  createdAt: string | null
  billCount: number
  boundChannels: number
}

export interface AdminUserPage {
  page: number
  pageSize: number
  total: number
  totalPages: number
  items: AdminUserRow[]
}

export interface AdminPushLogRow {
  id: number
  userId: number
  type: string
  dayKey: string
  pushedAt: number
}

export interface AdminPushLogPage {
  page: number
  pageSize: number
  total: number
  totalPages: number
  items: AdminPushLogRow[]
}

export interface AdminBotStatus {
  configured: boolean
  maskedAppId: string | null
  tokenExpiresAt: number | null
  gatewayOnline: boolean
  gatewayStarted: boolean
}

export function getAdminOverview() {
  return api<AdminOverview>('/api/admin/overview')
}

export function getAdminUsers(query?: string, page = 1) {
  return api<AdminUserPage>('/api/admin/users', { query: { query, page } })
}

export function getAdminPushLogs(page = 1) {
  return api<AdminPushLogPage>('/api/admin/push-logs', { query: { page } })
}

export function getAdminBotStatus() {
  return api<AdminBotStatus>('/api/admin/bot/status')
}
