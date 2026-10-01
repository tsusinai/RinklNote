import { defineStore } from 'pinia'
import { categories } from '../api/categories'
import { accounts } from '../api/accounts'
import { fetchAllBills } from '../api/bills'
import { keywords } from '../api/keywords'
import { HttpError, type ErrorKind } from '../api/http'
import { useAuthStore } from './auth'
import type { Bill, Category, Account, Keyword } from '../types'

/* ── 同步结果模型（Task 4）────────────────────────────────────────────
 * Web 的「同步」= 从云端刷新数据（分类 / 账户 / 账单 / 关键词），
 * 不是 App 的「待上传队列」——本地优先，失败时本地已有数据原样保留。
 * loadData 不再把失败资源静默替换为空数组后照样报「同步完成」，
 * 而是返回结构化结果，由页面（SyncCard 等）如实展示状态、原因与受影响资源。 */

export type SyncStatus =
  | 'idle'           // 启动后尚未尝试
  | 'success'        // 本次尝试的所有资源全部拉取成功
  | 'partial'        // 部分资源成功、部分失败（失败资源保留本地旧数据）
  | 'error'          // 本次尝试的资源全部失败（非网络原因）
  | 'offline'        // 网络不可用（本次尝试全部因网络层失败）
  | 'unauthorized'   // 401：需要重新登录
  | 'conflict'       // 409：并发冲突

export type SyncResource = 'cats' | 'accts' | 'bills' | 'keywords'

/** 单个资源的失败原因（statusCode=0 表示网络层失败，无 HTTP 状态码） */
export interface SyncError {
  resource: SyncResource
  kind: ErrorKind
  statusCode: number
  /** 面向用户的中文文案（来自 http.ts 的稳定映射） */
  message: string
}

/** loadData 的结构化结果 */
export interface SyncResult {
  status: SyncStatus
  /** 各资源本次是否加载成功（未尝试的资源为 false，如游客态不拉账单，不算失败） */
  resources: Record<SyncResource, boolean>
  /** 失败明细（供 UI 列出受影响资源） */
  errors: SyncError[]
  /** 首个失败的用户文案（整体提示用）；全部成功为 null */
  message: string | null
}

/** 资源中文名（供 UI 列出受影响资源） */
export const SYNC_RESOURCE_LABELS: Record<SyncResource, string> = {
  cats: '分类',
  accts: '账户',
  bills: '账单',
  keywords: '关键词',
}

/** 把任意异常归一为结构化失败原因（http.ts 已包装网络异常，这里只做兜底） */
function toSyncError(resource: SyncResource, e: unknown): SyncError {
  if (e instanceof HttpError) {
    return { resource, kind: e.kind, statusCode: e.statusCode, message: e.userMessage }
  }
  return { resource, kind: 'network', statusCode: 0, message: '网络不可用，请检查网络后重试' }
}

/** 由各资源成败推导整体状态（优先级：需要登录 > 并发冲突 > 网络不可用 > 全部失败 > 部分失败） */
function buildSyncResult(resources: Record<SyncResource, boolean>, errors: SyncError[]): SyncResult {
  if (errors.length === 0) {
    return { status: 'success', resources, errors, message: null }
  }
  const okCount = Object.values(resources).filter(Boolean).length
  const byKind = (k: ErrorKind) => errors.some((e) => e.kind === k)
  let status: SyncStatus
  if (byKind('unauthorized')) status = 'unauthorized'
  else if (byKind('conflict')) status = 'conflict'
  else if (okCount === 0 && errors.every((e) => e.kind === 'network')) status = 'offline'
  else if (okCount === 0) status = 'error'
  else status = 'partial'
  return { status, resources, errors, message: errors[0].message }
}

export const useDataStore = defineStore('data', {
  state: () => ({
    bills: [] as Bill[],
    cats: [] as Category[],
    accts: [] as Account[],
    keywords: [] as Keyword[],
    lastSync: 0,       // 上次「完全成功」的时间戳（部分失败不更新）
    lastAttempt: 0,    // 上次尝试时间（无论成败，供失败时展示「失败时间」）
    syncStatus: 'idle' as SyncStatus,
    lastResult: null as SyncResult | null,
  }),
  actions: {
    /**
     * 从云端刷新全部资源，返回结构化结果（不抛异常，调用方按结果提示）。
     * 失败资源一律保留本地旧数据，绝不静默替换为空数组。
     */
    async loadData(): Promise<SyncResult> {
      const auth = useAuthStore()
      const resources: Record<SyncResource, boolean> = { cats: false, accts: false, bills: false, keywords: false }
      const errors: SyncError[] = []

      // 分类与账户并行拉取；任一失败记入明细并保留旧数据。
      // 四个资源统一 allow401：401 变成 HttpError 交给下方状态推导，让 SyncCard 能
      // 呈现「需要重新登录 + 去登录」，而不是在 api() 内部被清 token + 硬跳转劫持
      // （体验审查 P2：unauthorized 状态此前基本不可见）。
      await Promise.all([
        categories.list({ allow401: true }).then(
          (list) => { this.cats = list; resources.cats = true },
          (e) => { errors.push(toSyncError('cats', e)) },
        ),
        accounts.list({ allow401: true }).then(
          (list) => { this.accts = list; resources.accts = true },
          (e) => { errors.push(toSyncError('accts', e)) },
        ),
      ])

      // 账单与关键词：仅登录后拉取；游客态不拉，不算失败
      if (auth.token) {
        try {
          const all = await fetchAllBills({ allow401: true })
          this.bills = all.reverse() // newest first（与原 web 一致）
          resources.bills = true
          try {
            this.keywords = await keywords.list({ allow401: true })
            resources.keywords = true
          } catch (e) {
            errors.push(toSyncError('keywords', e))
          }
        } catch (e) {
          errors.push(toSyncError('bills', e))
        }
      }

      const result = buildSyncResult(resources, errors)
      this.lastAttempt = Date.now()
      this.syncStatus = result.status
      this.lastResult = result
      // 「上次成功」只记完全成功的刷新；部分失败时保留更早的成功时间，不伪造
      if (result.status === 'success') this.lastSync = this.lastAttempt
      return result
    },
    async refreshAccounts() {
      try { this.accts = await accounts.list() } catch { /* 保留旧数据 */ }
    },
    async loadKeywords() {
      if (!useAuthStore().token) { this.keywords = []; return }
      try { this.keywords = await keywords.list() } catch { /* 保留旧数据 */ }
    },
    /** 退出登录时清空同步状态，避免上一账号的失败状态串到下一账号 */
    resetSyncState() {
      this.lastSync = 0
      this.lastAttempt = 0
      this.syncStatus = 'idle'
      this.lastResult = null
    },
  },
})
