import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'
import { installTransition } from './transition'

/* 路由 meta 类型补充：title 驱动 document.title 与移动端顶栏；tab 为控制台 tab 序号（转场方向判定） */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    tab?: number
  }
}

/* 控制台子路由：meta.tab 按底栏顺序编号（记账0/账单1/图表2/资产3/我的4），
 * 设置不占底栏（入口在我的页与桌面侧栏），序号顺延为 5，与侧栏分组顺序一致 */
const consoleChildren = [
  { path: '', name: 'bookkeeping', component: () => import('../views/console/Bookkeeping.vue'), meta: { title: '记账', tab: 0 } },
  { path: 'bills', name: 'bills', component: () => import('../views/console/Bills.vue'), meta: { title: '账单', tab: 1 } },
  { path: 'charts', name: 'charts', component: () => import('../views/console/Charts.vue'), meta: { title: '图表', tab: 2 } },
  { path: 'assets', name: 'assets', component: () => import('../views/console/Assets.vue'), meta: { title: '资产', tab: 3 } },
  { path: 'me', name: 'me', component: () => import('../views/console/Me.vue'), meta: { title: '我的', tab: 4 } },
  { path: 'settings', name: 'settings', component: () => import('../views/console/Settings.vue'), meta: { title: '设置', tab: 5 } },
]

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/Landing.vue'), meta: { title: '记一笔' } },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  { path: '/console', name: 'console', component: () => import('../views/console/ConsoleLayout.vue'), children: consoleChildren },
  /* 404 兜底：其余任何路径进 NotFound 页 */
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFound.vue'), meta: { title: '页面不存在' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  /* 路由切换一律回到页顶（对齐 App 切页不保留滚动位置） */
  scrollBehavior: () => ({ top: 0 }),
})

installGuards(router)
installTransition(router)
export default router
