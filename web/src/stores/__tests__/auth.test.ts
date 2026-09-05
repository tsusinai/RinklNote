import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'

beforeEach(() => setActivePinia(createPinia()))

describe('auth store', () => {
  it('login stores token and marks ready', async () => {
    const s = useAuthStore()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ status: 200, ok: true, json: async () => ({ token: 't', userId: 1 }) } as unknown as Response)
    await s.login('123', 'pwd')
    expect(s.token).toBe('t')
    expect(localStorage.getItem('rkl_token')).toBe('t')
  })
  it('logout clears token and user', async () => {
    const s = useAuthStore(); s.token = 't'; s.user = { id: 1, phone: null, qqNumber: null, qqOpenid: null, createdAt: null, aiDisabled: false }
    s.logout()
    expect(s.token).toBeNull(); expect(s.user).toBeNull(); expect(localStorage.getItem('rkl_token')).toBeNull()
  })
  it('login throws when server returns no token (bad credentials)', async () => {
    const s = useAuthStore()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ status: 401, json: async () => ({ message: '手机号或密码错误' }) } as unknown as Response)
    await expect(s.login('123', 'wrong')).rejects.toThrow('手机号或密码错误')
    expect(s.token).toBeNull()
    expect(localStorage.getItem('rkl_token')).toBeNull()
  })
})
