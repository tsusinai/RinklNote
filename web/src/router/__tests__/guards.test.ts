import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import { authGuard, sanitizeRedirect } from '../guards'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })

describe('authGuard', () => {
  it('allows unauthenticated landing / (展示页放行)', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/' })).toBe(true)
  })
  it('redirects unauthenticated /console to /login with redirect query', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/console' })).toEqual({ path: '/login', query: { redirect: '/console' } })
  })
  it('redirects authenticated /login to /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/login' })).toBe('/console')
  })
  it('authenticated /login with in-app redirect goes back to the target', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/login', query: { redirect: '/console/bills' } })).toBe('/console/bills')
  })
  it('authenticated /login with unsafe redirect falls back to /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/login', query: { redirect: 'https://evil.example' } })).toBe('/console')
  })
  it('allows authenticated /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/console' })).toBe(true)
  })
  it('redirects unauthenticated /console/bills to /login carrying fullPath', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/console/bills' })).toEqual({ path: '/login', query: { redirect: '/console/bills' } })
  })
  it('carries query string via fullPath on redirect', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/console/bills', fullPath: '/console/bills?month=2026-09' }))
      .toEqual({ path: '/login', query: { redirect: '/console/bills?month=2026-09' } })
  })
  it('allows authenticated /console/bills', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/console/bills' })).toBe(true)
  })
  it('treats a stale literal "undefined" token as logged-out (store + guard)', async () => {
    localStorage.setItem('rkl_token', 'undefined')
    const s = useAuthStore() // state initializes token via getToken()
    expect(s.token).toBeNull()
    expect(await authGuard({ path: '/console' })).toEqual({ path: '/login', query: { redirect: '/console' } })
  })
})

describe('authGuard /admin（管理端守卫）', () => {
  it('redirects non-admin user to 404 (不暴露管理端存在)', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true; s.user = { isAdmin: false } as any
    expect(await authGuard({ path: '/admin' })).toEqual({ name: 'not-found' })
    expect(await authGuard({ path: '/admin/users' })).toEqual({ name: 'not-found' })
  })
  it('redirects unauthenticated /admin to 404 (不送登录页防探测)', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/admin' })).toEqual({ name: 'not-found' })
  })
  it('allows admin user into /admin', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true; s.user = { isAdmin: true } as any
    expect(await authGuard({ path: '/admin' })).toBe(true)
    expect(await authGuard({ path: '/admin/push-logs' })).toBe(true)
  })
  it('while user profile not loaded yet, falls through (hydrate 兜底由 ready 前置保证)', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true; s.user = null
    // user 为 null（如 /me 失败）时不放行——保守视作非管理员
    expect(await authGuard({ path: '/admin' })).toEqual({ name: 'not-found' })
  })
})

describe('sanitizeRedirect', () => {
  it('accepts in-app absolute paths', () => {
    expect(sanitizeRedirect('/console/bills')).toBe('/console/bills')
  })
  it('rejects protocol-relative and external URLs', () => {
    expect(sanitizeRedirect('//evil.example')).toBeNull()
    expect(sanitizeRedirect('https://evil.example')).toBeNull()
    expect(sanitizeRedirect('javascript:alert(1)')).toBeNull()
  })
  it('rejects non-string values', () => {
    expect(sanitizeRedirect(undefined)).toBeNull()
    expect(sanitizeRedirect(['//x'])).toBeNull()
  })
})
