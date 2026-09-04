import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'

export const authGuard = async (to: any) => {
  const store = useAuthStore()
  if (!store.ready) await store.hydrate()
  const isConsole = to.path === '/console' || to.path.startsWith('/console/')
  if (isConsole && !store.token) return '/login'
  if (to.path === '/login' && store.token) return '/console'
  return true
}

export function installGuards(router: Router) { router.beforeEach(authGuard) }
