// 测试 insights month 拼串
import { describe, it, expect, vi, afterEach } from 'vitest'
import { insights } from '../insights'

afterEach(() => vi.restoreAllMocks())

describe('insights', () => {
  it('monthlyReview uses month query', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ status: 200, json: async () => ({ summary: 's' }) }) as any
    await insights.monthlyReview('2026-08')
    expect(globalThis.fetch).toHaveBeenCalledWith(expect.stringContaining('month=2026-08'), expect.anything())
  })
})
