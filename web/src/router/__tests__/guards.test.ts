import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import { authGuard } from '../guards'

beforeEach(() => setActivePinia(createPinia()))

describe('authGuard', () => {
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
})
