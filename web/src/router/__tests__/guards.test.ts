import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import { authGuard } from '../guards'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })

describe('authGuard', () => {
  it('allows unauthenticated landing / (展示页放行)', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/' })).toBe(true)
  })
  it('redirects unauthenticated /console to /login', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/console' })).toBe('/login')
  })
  it('redirects authenticated /login to /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/login' })).toBe('/console')
  })
  it('allows authenticated /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/console' })).toBe(true)
  })
  it('redirects unauthenticated /console/bills to /login', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/console/bills' })).toBe('/login')
  })
  it('allows authenticated /console/bills', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/console/bills' })).toBe(true)
  })
  it('treats a stale literal "undefined" token as logged-out (store + guard)', async () => {
    localStorage.setItem('rkl_token', 'undefined')
    const s = useAuthStore() // state initializes token via getToken()
    expect(s.token).toBeNull()
    expect(await authGuard({ path: '/console' })).toBe('/login')
  })
})
