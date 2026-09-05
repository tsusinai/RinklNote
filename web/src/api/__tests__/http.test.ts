import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, setToken, getToken, clearToken } from '../http'

function mockFetch(status: number, json: unknown) {
  return vi.fn().mockResolvedValue({ status, ok: status >= 200 && status < 300, json: async () => json })
}

describe('api', () => {
  beforeEach(() => { localStorage.clear(); clearToken() })
  afterEach(() => { vi.restoreAllMocks() })

  it('sends Bearer token and returns json', async () => {
    setToken('abc')
    const f = mockFetch(200, { ok: true })
    globalThis.fetch = f as any
    const r = await api('/api/bills/sync')
    expect(f).toHaveBeenCalledWith('/api/bills/sync', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc' }),
    }))
    expect(r).toEqual({ ok: true })
  })

  it('clears token and redirects on 401 with a token', async () => {
    setToken('abc')
    const f = mockFetch(401, {})
    globalThis.fetch = f as any
    let redirected = ''
    const orig = window.location; Object.defineProperty(window, 'location', { value: { ...orig, href: '' }, configurable: true })
    Object.defineProperty(window.location, 'href', { set: (v: string) => { redirected = v }, get: () => '' })
    await api('/api/x')
    expect(localStorage.getItem('rkl_token')).toBeNull()
    expect(redirected).toBe('/login')
    Object.defineProperty(window, 'location', { value: orig, configurable: true })
  })

  it('preserves 409 as HttpError for conditional PUT', async () => {
    const f = mockFetch(409, { message: 'conflict' })
    globalThis.fetch = f as any
    await expect(api('/api/bills/1', { method: 'PUT' })).rejects.toMatchObject({ statusCode: 409 })
  })

  it('allow401 throws HttpError instead of redirecting', async () => {
    setToken('abc')
    const f = mockFetch(401, { message: '原密码错误' })
    globalThis.fetch = f as any
    await expect(api('/api/auth/password', { method: 'POST', allow401: true })).rejects.toMatchObject({ statusCode: 401, message: '原密码错误' })
  })
})
