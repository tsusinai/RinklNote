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
