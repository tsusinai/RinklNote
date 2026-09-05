import { describe, it, expect, vi, afterEach } from 'vitest'
import { api } from '../http'

afterEach(() => vi.restoreAllMocks())

describe('api 409 HttpError carries body', () => {
  it('attaches fresh row to HttpError.data for conditional PUT', async () => {
    const fresh = { id: 3, updatedAt: 9998746500000, amount: 19.9 }
    globalThis.fetch = vi.fn().mockResolvedValue({ status: 409, json: async () => fresh }) as any
    try {
      await api('/api/bills/3', { method: 'PUT', body: {} })
    } catch (e: any) {
      expect(e.statusCode).toBe(409)
      expect(e.data).toEqual(fresh)
    }
  })
})
