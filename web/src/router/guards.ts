import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'

/** 仅放行站内相对路径（以单个 / 开头），拒绝 // 与外站协议串，防开放重定向 */
export function sanitizeRedirect(r: unknown): string | null {
  if (typeof r !== 'string' || !r.startsWith('/') || r.startsWith('//')) return null
  return r
}

export const authGuard = async (to: any) => {
  const store = useAuthStore()
  if (!store.ready) await store.hydrate()
  const isConsole = to.path === '/console' || to.path.startsWith('/console/')
  // 未登录访问控制台 → 登录页并带上回跳地址（登录成功后原路返回，Login.vue 读取）
  if (isConsole && !store.token) {
    return { path: '/login', query: { redirect: to.fullPath ?? to.path } }
  }
  // 已登录访问登录页 → 带 redirect 时直接回跳，否则进控制台
  if (to.path === '/login' && store.token) {
    return sanitizeRedirect(to.query?.redirect) ?? '/console'
  }
  return true
}

/** document.title 随路由切换：RinklNote · {meta.title}（登录/落地/404 各用各自标题） */
export function installTitleSync(router: Router) {
  router.afterEach((to) => {
    document.title = to.meta.title ? `RinklNote · ${to.meta.title}` : 'RinklNote'
  })
}

export function installGuards(router: Router) {
  router.beforeEach(authGuard)
  installTitleSync(router)
}
