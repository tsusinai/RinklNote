import { describe, it, expect, vi } from 'vitest'
import { fetchAllBills } from '../bills'

function page(bills: any[], hasMore: boolean, nextAfter?: number | null, nextAfterId?: number | null) {
  return { bills, serverTime: 0, hasMore, nextAfter: nextAfter ?? null, nextAfterId: nextAfterId ?? null }
}

describe('fetchAllBills', () => {
  it('paginates through composite cursor until hasMore is false', async () => {
    // fetchAllBills keeps paging only while the last page came back full (===200).
    // So page 1 must be a full 200-item page to trigger the next call.
    const first = Array.from({ length: 200 }, (_, i) => ({ id: i + 1, updatedAt: 10 }))
    const pages = [
      page(first, true, 10, null),
      page([{ id: 201, updatedAt: 20 }], false, 20, null),
    ]
    const calls: string[] = []
    globalThis.fetch = vi.fn(async (url: string) => {
      calls.push(url)
      const p = pages.shift()!
      return { status: 200, json: async () => p }
    }) as any
    const all = await fetchAllBills()
    expect(all.length).toBe(201)
    expect(calls[1]).toContain('after=10')
  })
})
