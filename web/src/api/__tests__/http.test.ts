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

  it('clears token and redirects to login with redirect param on 401 with a token', async () => {
    setToken('abc')
    const f = mockFetch(401, {})
    globalThis.fetch = f as any
    let redirected = ''
    // 显式 mock pathname，验证回跳地址携带当前页面（修「401 丢回跳」）
    const orig = window.location; Object.defineProperty(window, 'location', { value: { pathname: '/console/bills', search: '', hash: '' }, configurable: true })
    Object.defineProperty(window.location, 'href', { set: (v: string) => { redirected = v }, get: () => '' })
    await api('/api/x')
    expect(localStorage.getItem('rkl_token')).toBeNull()
    expect(redirected).toBe('/login?redirect=%2Fconsole%2Fbills')
    Object.defineProperty(window, 'location', { value: orig, configurable: true })
  })

  it('redirects to plain /login on 401 when already on the login page', async () => {
    setToken('abc')
    const f = mockFetch(401, {})
    globalThis.fetch = f as any
    let redirected = ''
    const orig = window.location; Object.defineProperty(window, 'location', { value: { pathname: '/login', search: '', hash: '' }, configurable: true })
    Object.defineProperty(window.location, 'href', { set: (v: string) => { redirected = v }, get: () => '' })
    await api('/api/x')
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

describe('getToken', () => {
  it('returns a normal token', () => {
    localStorage.setItem('rkl_token', 'abc')
    expect(getToken()).toBe('abc')
  })
  it('treats a stale literal "undefined" token as logged-out', () => {
    localStorage.setItem('rkl_token', 'undefined')
    expect(getToken()).toBeNull()
  })
  it('treats a stale literal "null" token as logged-out', () => {
    localStorage.setItem('rkl_token', 'null')
    expect(getToken()).toBeNull()
  })
  it('returns null for an empty token', () => {
    localStorage.setItem('rkl_token', '')
    expect(getToken()).toBeNull()
  })
})

describe('错误类型映射（Task 4：502/503/504/网络异常 → 稳定中文错误类型）', () => {
  // 独立于上面 describe 的 beforeEach：清掉可能残留的 token，避免 401 用例走跳转分支
  beforeEach(() => { localStorage.clear(); clearToken() })

  it.each([502, 503, 504])('HTTP %i → badGateway：保留原始状态码，文案稳定不随报文漂移', async (status) => {
    // 网关错误常见 HTML 报文，body.message 不存在也不影响 userMessage 稳定
    globalThis.fetch = mockFetch(status, '<html>Bad Gateway</html>') as any
    await expect(api('/api/x')).rejects.toMatchObject({
      statusCode: status,
      kind: 'badGateway',
      userMessage: '服务暂时不可用，请稍后重试',
    })
  })

  it('HTTP 500 → server', async () => {
    globalThis.fetch = mockFetch(500, {}) as any
    await expect(api('/api/x')).rejects.toMatchObject({
      statusCode: 500,
      kind: 'server',
      userMessage: '服务出错了，请稍后重试',
    })
  })

  it('HTTP 404 → http（未特判的常规 HTTP 错误）', async () => {
    globalThis.fetch = mockFetch(404, {}) as any
    await expect(api('/api/x')).rejects.toMatchObject({ statusCode: 404, kind: 'http' })
  })

  it('fetch 直接失败（断网/超时中断）→ network，statusCode=0', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch')) as any
    await expect(api('/api/x')).rejects.toMatchObject({
      statusCode: 0,
      kind: 'network',
      userMessage: '网络不可用，请检查网络后重试',
    })
  })

  it('409 → conflict：userMessage 稳定，message 保留原始信息（供冲突处理逻辑使用）', async () => {
    globalThis.fetch = mockFetch(409, { message: 'conflict' }) as any
    try {
      await api('/api/bills/1', { method: 'PUT' })
      expect.unreachable('应当抛出 HttpError')
    } catch (e: any) {
      expect(e.kind).toBe('conflict')
      expect(e.statusCode).toBe(409)
      expect(e.userMessage).toBe('数据已被其他设备修改，请刷新后再试')
      expect(e.message).toBe('并发冲突') // 409 分支的历史行为：message 为固定中文，body 挂在 data 上
    }
  })

  it('401 → unauthorized：userMessage 为「重新登录」行动文案', async () => {
    globalThis.fetch = mockFetch(401, {}) as any
    // 无 token 时 401 不触发跳转，直接抛 HttpError
    await expect(api('/api/x')).rejects.toMatchObject({
      statusCode: 401,
      kind: 'unauthorized',
      userMessage: '登录已过期，请重新登录',
    })
  })
})
