import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '../data'
import { useAuthStore } from '../auth'

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.restoreAllMocks())

describe('data store loadData', () => {
  it('loads cats and accts', async () => {
    globalThis.fetch = vi.fn(async (url: string) => {
      if (url.includes('/categories')) return { status: 200, ok: true, json: async () => [{ id: 1, name: '三餐', billType: 'EXPENSE', subCategories: [] }] }
      if (url.includes('/accounts')) return { status: 200, ok: true, json: async () => [{ id: 1, name: '微信', iconColor: 'x', balance: 0 }] }
      return { status: 200, ok: true, json: async () => [] }
    }) as any
    const s = useDataStore()
    await s.loadData()
    expect(s.cats.length).toBe(1)
    expect(s.accts.length).toBe(1)
  })
  it('loads bills reversed newest-first when authed', async () => {
    useAuthStore().token = 't'
    const calls: string[] = []
    globalThis.fetch = vi.fn(async (url: string) => {
      calls.push(url)
      if (url.includes('/sync')) return { status: 200, ok: true, json: async () => ({ bills: [{ id: 1, updatedAt: 1 }, { id: 2, updatedAt: 2 }], hasMore: false, nextAfter: null, nextAfterId: null }) }
      if (url.includes('/categories')) return { status: 200, ok: true, json: async () => [] }
      if (url.includes('/accounts')) return { status: 200, ok: true, json: async () => [] }
      if (url.includes('/keywords')) return { status: 200, ok: true, json: async () => [] }
      return { status: 200, ok: true, json: async () => [] }
    }) as any
    const s = useDataStore()
    await s.loadData()
    expect(s.bills.map((b: any) => b.id)).toEqual([2, 1]) // reversed newest first
  })
})

/* ── Task 4：loadData 结构化结果（Web 同步语义 = 从云端刷新，失败不静默置空）────────── */

/** 登录态下全资源成功的通用 fetch mock */
function okFetch() {
  return vi.fn(async (url: string) => {
    if (url.includes('/sync')) return { status: 200, ok: true, json: async () => ({ bills: [{ id: 1, updatedAt: 1 }], hasMore: false, nextAfter: null, nextAfterId: null }) }
    return { status: 200, ok: true, json: async () => [] }
  })
}

describe('loadData 结构化结果（Task 4）', () => {
  it('部分资源失败：结果为 partial，失败资源保留旧数据不置空，错误含稳定文案与状态码', async () => {
    useAuthStore().token = 't'
    const s = useDataStore()
    s.cats = [{ id: 9, name: '旧分类', type: 'EXPENSE', iconName: '', subCategories: [] }]
    globalThis.fetch = vi.fn(async (url: string) => {
      if (url.includes('/categories')) return { status: 500, ok: false, json: async () => ({}) }
      if (url.includes('/sync')) return { status: 200, ok: true, json: async () => ({ bills: [], hasMore: false, nextAfter: null, nextAfterId: null }) }
      return { status: 200, ok: true, json: async () => [] }
    }) as any
    const r = await s.loadData()
    expect(r.status).toBe('partial')
    expect(s.cats[0]?.name).toBe('旧分类') // 失败资源保留本地旧数据，绝不静默替换为空数组
    expect(r.errors[0]).toMatchObject({ resource: 'cats', statusCode: 500, message: '服务出错了，请稍后重试' })
    expect(r.message).toBe('服务出错了，请稍后重试')
    expect(s.syncStatus).toBe('partial')
    expect(s.lastSync).toBe(0) // 部分失败不更新「上次成功」，不伪造全绿
  })

  it('全部资源网络失败：结果为 offline，本地账单保留，受影响资源逐项列出', async () => {
    useAuthStore().token = 't'
    const s = useDataStore()
    s.bills = [{ id: 7 } as any] // 旧账单
    globalThis.fetch = vi.fn(async () => { throw new TypeError('Failed to fetch') }) as any
    const r = await s.loadData()
    expect(r.status).toBe('offline')
    expect(s.bills[0]?.id).toBe(7) // 本地数据原样保留
    expect(r.errors.map((e) => e.resource).sort()).toEqual(['accts', 'bills', 'cats'])
    expect(r.errors.every((e) => e.kind === 'network' && e.statusCode === 0)).toBe(true)
    expect(s.syncStatus).toBe('offline')
  })

  it('409 并发冲突：结果为 conflict（优先级高于部分成功）', async () => {
    useAuthStore().token = 't'
    const s = useDataStore()
    globalThis.fetch = vi.fn(async (url: string) => {
      if (url.includes('/categories')) return { status: 409, ok: false, json: async () => ({}) }
      if (url.includes('/sync')) return { status: 200, ok: true, json: async () => ({ bills: [], hasMore: false, nextAfter: null, nextAfterId: null }) }
      return { status: 200, ok: true, json: async () => [] }
    }) as any
    const r = await s.loadData()
    expect(r.status).toBe('conflict')
    expect(r.errors[0]).toMatchObject({ resource: 'cats', kind: 'conflict', statusCode: 409 })
  })

  it('401 需要重新登录：结果为 unauthorized', async () => {
    // 已登录但 token 失效的真实场景：loadData 全部资源走 allow401，
    // 401 落为 unauthorized 结果而不是被 api() 清 token + 硬跳转劫持。
    useAuthStore().token = 't'
    const s = useDataStore()
    globalThis.fetch = vi.fn(async () => ({ status: 401, ok: false, json: async () => ({}) })) as any
    const r = await s.loadData()
    expect(r.status).toBe('unauthorized')
    expect(r.errors[0]).toMatchObject({ kind: 'unauthorized', message: '登录已过期，请重新登录' })
  })

  it('游客态：不拉账单也不算失败，分类账户成功 → success 且更新 lastSync', async () => {
    useAuthStore().token = null
    const s = useDataStore()
    const calls: string[] = []
    globalThis.fetch = vi.fn(async (url: string) => {
      calls.push(url)
      return { status: 200, ok: true, json: async () => [] }
    }) as any
    const r = await s.loadData()
    expect(r.status).toBe('success')
    expect(r.resources.bills).toBe(false) // 未尝试 ≠ 失败
    expect(calls.some((u) => u.includes('/sync'))).toBe(false)
    expect(s.lastSync).toBeGreaterThan(0)
  })

  it('完全成功才更新 lastSync；成功后失败详情清空（syncStatus 回 success）', async () => {
    useAuthStore().token = 't'
    const s = useDataStore()
    globalThis.fetch = okFetch() as any
    await s.loadData()
    expect(s.syncStatus).toBe('success')
    expect(s.lastSync).toBeGreaterThan(0)
    expect(s.lastResult?.errors).toEqual([])
  })
})
