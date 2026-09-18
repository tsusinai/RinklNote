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

/* 控制台子路由：meta.tab 按导航顺序编号（记账0/账单1/图表2/资产3/多币种4/我的5），settings=6。
 * 多币种 currency 为二级页（桌面侧栏资产组 + 我的页入口，不占移动端底栏），
 * tab 序号仅用于转场方向判定，保持与导航顺序一致即可。 */
const consoleChildren = [
  { path: '', name: 'bookkeeping', component: () => import('../views/console/Bookkeeping.vue'), meta: { title: '记账', tab: 0 } },
  { path: 'bills', name: 'bills', component: () => import('../views/console/Bills.vue'), meta: { title: '账单', tab: 1 } },
  { path: 'charts', name: 'charts', component: () => import('../views/console/Charts.vue'), meta: { title: '图表', tab: 2 } },
  { path: 'assets', name: 'assets', component: () => import('../views/console/Assets.vue'), meta: { title: '资产', tab: 3 } },
  { path: 'currency', name: 'currency', component: () => import('../views/console/Currency.vue'), meta: { title: '多币种', tab: 4 } },
  { path: 'me', name: 'me', component: () => import('../views/console/Me.vue'), meta: { title: '我的', tab: 5 } },
  { path: 'settings', name: 'settings', component: () => import('../views/console/Settings.vue'), meta: { title: '设置', tab: 6 } },
]

/* 管理端子路由（/admin，守卫把非管理员 404 化——见 guards.ts）。
 * 页面全只读；配置写操作留在用户控制台「设置 → 多通道机器人」。 */
const adminChildren = [
  { path: '', name: 'admin-overview', component: () => import('../views/admin/AdminOverview.vue'), meta: { title: '运维大盘' } },
  { path: 'users', name: 'admin-users', component: () => import('../views/admin/AdminUsers.vue'), meta: { title: '用户管理' } },
  { path: 'bots', name: 'admin-bots', component: () => import('../views/admin/AdminBots.vue'), meta: { title: '机器人运维' } },
  { path: 'push-logs', name: 'admin-push-logs', component: () => import('../views/admin/AdminPushLogs.vue'), meta: { title: '推送历史' } },
]

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/Landing.vue'), meta: { title: '记一笔' } },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  { path: '/console', name: 'console', component: () => import('../views/console/ConsoleLayout.vue'), children: consoleChildren },
  { path: '/admin', name: 'admin', component: () => import('../views/admin/AdminLayout.vue'), children: adminChildren },
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
