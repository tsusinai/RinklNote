import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'

beforeEach(() => setActivePinia(createPinia()))

describe('auth store', () => {
  it('login stores token and marks ready', async () => {
    const s = useAuthStore()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ status: 200, json: async () => ({ token: 't', userId: 1 }) } as unknown as Response)
    await s.login('123', 'pwd')
    expect(s.token).toBe('t')
    expect(localStorage.getItem('rkl_token')).toBe('t')
  })
  it('logout clears token and user', async () => {
    const s = useAuthStore(); s.token = 't'; s.user = { id: 1, phone: null, qqNumber: null, qqOpenid: null, createdAt: null, aiDisabled: false }
    s.logout()
    expect(s.token).toBeNull(); expect(s.user).toBeNull(); expect(localStorage.getItem('rkl_token')).toBeNull()
  })
})
