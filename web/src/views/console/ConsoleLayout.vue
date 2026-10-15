<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useThemeStore } from '../../stores/theme'
import { useDataStore } from '../../stores/data'
import Icon from '../../components/ui/Icon.vue'
import CommandPalette from '../../components/CommandPalette.vue'
import type { IconName } from '../../components/ui/icons'
import { pageTransition } from '../../router/transition'

/* 控制台布局壳：桌面用深色工作台侧栏，移动用轻量顶栏与底栏。
 * 「设置」不占移动端底栏，入口在我的页与桌面侧栏；颜色全部走 CSS 变量。
 * Ctrl+K（Task 3.4）：全局命令面板快捷键（mac ⌘K 同样生效）——
 * 修饰键组合无「输入冲突」，输入框聚焦时也响应；纯按键快捷键由各页面自行挂接并守卫可编辑元素。 */
const route = useRoute()
const router = useRouter()
const store = useAuthStore()
const theme = useThemeStore()
const data = useDataStore()

const paletteOpen = ref(false)
function onGlobalKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && !e.altKey && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault() // 拦截浏览器「焦点地址栏搜索」默认行为
    paletteOpen.value = !paletteOpen.value
  }
}
onMounted(() => window.addEventListener('keydown', onGlobalKey))
onUnmounted(() => window.removeEventListener('keydown', onGlobalKey))

interface NavItem { name: string; label: string; icon: IconName }
const groups: { title: string; items: NavItem[] }[] = [
  { title: '记账', items: [
    { name: 'bookkeeping', label: '记账', icon: 'book' },
    { name: 'bills', label: '账单', icon: 'receipt' },
    { name: 'charts', label: '图表', icon: 'chart' },
  ] },
  { title: '资产', items: [
    { name: 'assets', label: '资产', icon: 'wallet' },
    { name: 'currency', label: '多币种', icon: 'wallet' },
  ] },
  { title: '我的', items: [
    { name: 'me', label: '我的', icon: 'user' },
    { name: 'settings', label: '设置', icon: 'settings' },
  ] },
]
/* 移动端底栏固定 5 tab（多币种为二级页，与设置一样不占底栏） */
const mobileTabs: NavItem[] = groups
  .flatMap((g) => g.items)
  .filter((it) => it.name !== 'settings' && it.name !== 'currency')

const pageTitle = computed(() => route.meta.title ?? '')
/* 用户卡：手机号打掩码，QQ 账号显示 QQ 号 */
const userLabel = computed(() => {
  const p = store.user?.phone
  if (p && /^\d{11}$/.test(p)) return `${p.slice(0, 3)}****${p.slice(7)}`
  return p || store.user?.qqNumber || '未登录'
})
/* 当前生效是否暗色（显式选择直接读，跟随系统时读系统偏好） */
const isDark = computed(() => {
  if (theme.theme === 'dark') return true
  if (theme.theme === 'light') return false
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
})

function go(name: string) { router.push({ name }) }
function toggleTheme() { theme.toggle() }
function logout() {
  store.logout()
  // 清空控制台缓存，避免下个账号看到上个账号的数据
  data.bills = []; data.cats = []; data.accts = []; data.keywords = []; data.resetSyncState()
  router.push('/login')
}
</script>

<template>
  <div class="console">
    <a class="skip-link" href="#console-main">跳到主要内容</a>
    <!-- 桌面侧栏：用几何标志替代旧头像，保持财务工作台的专业感 -->
    <aside class="sidebar">
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 40 40" aria-hidden="true">
          <path d="M8 10h24v7H18v6h14v7H8V10Z" />
          <path d="M8 10v20" class="mark-cut" />
        </svg>
        <div>
          <span class="brand-name">记一笔</span>
          <span class="brand-kicker">个人财务工作台</span>
        </div>
      </div>
      <!-- 命令面板入口（桌面侧栏）：点按等价 Ctrl+K -->
      <button class="palette-entry" type="button" @click="paletteOpen = true">
        <Icon name="search" :size="16" />
        <span>快捷操作</span>
        <kbd>Ctrl K</kbd>
      </button>
      <nav class="nav">
        <div v-for="g in groups" :key="g.title" class="nav-group">
          <div class="nav-title">{{ g.title }}</div>
          <button
            v-for="it in g.items" :key="it.name"
            class="nav-item" :class="{ active: route.name === it.name }"
            :aria-current="route.name === it.name ? 'page' : undefined"
            @click="go(it.name)"
          >
            <Icon :name="it.icon" :size="18" />
            <span>{{ it.label }}</span>
          </button>
        </div>
      </nav>
      <div class="side-footer">
        <div class="user-card">
          <Icon name="user" :size="16" />
          <span class="user-label">{{ userLabel }}</span>
        </div>
        <button class="nav-item logout" @click="logout">
          <Icon name="logout" :size="18" />
          <span>退出登录</span>
        </button>
      </div>
    </aside>

    <!-- 移动端顶栏：页面标题 + 命令面板 + 主题切换 -->
    <header class="topbar">
      <span class="topbar-title">{{ pageTitle }}</span>
      <span class="topbar-actions">
        <button class="theme-btn" aria-label="打开命令面板" @click="paletteOpen = true">
          <Icon name="search" :size="18" />
        </button>
        <button class="theme-btn" :aria-label="isDark ? '切换为亮色' : '切换为暗色'" @click="toggleTheme">
          <Icon :name="isDark ? 'sun' : 'moon'" :size="18" />
        </button>
      </span>
    </header>

    <!-- 内层路由：控制台内部按 tab 序号判方向转场（page-forward/back），进出控制台由 App 层 fade -->
    <main id="console-main" class="content" tabindex="-1">
      <RouterView v-slot="{ Component: Child, route: childRoute }">
        <Transition :name="pageTransition" mode="out-in">
          <component :is="Child" :key="childRoute.path" />
        </Transition>
      </RouterView>
    </main>

    <!-- 移动端底栏：固定 5 tab -->
    <nav class="bottom-nav">
      <button
        v-for="t in mobileTabs" :key="t.name"
        class="tab" :class="{ active: route.name === t.name }"
        :aria-current="route.name === t.name ? 'page' : undefined"
        @click="go(t.name)"
      >
        <Icon :name="t.icon" :size="20" />
        <span>{{ t.label }}</span>
      </button>
    </nav>

    <!-- Ctrl+K 命令面板（全控制台共用一个实例） -->
    <CommandPalette :open="paletteOpen" @close="paletteOpen = false" />
  </div>
</template>

<style scoped>
.console {
  --bg: #f4f1eb; --card: #fffdf8; --surface-2: #ebe6dd; --text: #17262d; --muted: #738087;
  --border: #d8d0c5; --border-light: #e7e0d7; --primary: #70b8e7; --primary-soft: #e3f1fa;
  --primary-ink: #214d68; --shadow-sm: 0 1px 2px rgba(27, 35, 38, .05); --shadow: 0 14px 32px rgba(27, 35, 38, .09);
  display: flex; min-height: 100vh; background: var(--bg); color: var(--text);
}
.skip-link { position: fixed; left: 16px; top: 12px; z-index: 300; padding: 9px 14px; border-radius: 8px; background: var(--primary); color: var(--primary-ink); font-weight: 700; transform: translateY(-180%); transition: transform var(--dur-expand) var(--ease); }
.skip-link:focus { transform: translateY(0); }
:global(:root[data-theme="dark"]) .console {
  --bg: #121d22; --card: #1b2a30; --surface-2: #24363d; --text: #edf4f5; --muted: #9eb0b6;
  --border: #38505a; --border-light: #2b4149; --primary: #86c9f0; --primary-soft: #1f4356;
  --primary-ink: #082333; --shadow-sm: 0 1px 2px rgba(0, 0, 0, .18); --shadow: 0 16px 34px rgba(0, 0, 0, .26);
}
@media (prefers-color-scheme: dark) {
  :global(:root:not([data-theme="light"])) .console {
    --bg: #121d22; --card: #1b2a30; --surface-2: #24363d; --text: #edf4f5; --muted: #9eb0b6;
    --border: #38505a; --border-light: #2b4149; --primary: #86c9f0; --primary-soft: #1f4356;
    --primary-ink: #082333; --shadow-sm: 0 1px 2px rgba(0, 0, 0, .18); --shadow: 0 16px 34px rgba(0, 0, 0, .26);
  }
}
.sidebar {
  width: 252px; flex-shrink: 0; padding: 30px 18px 22px; display: flex; flex-direction: column;
  background: #172b35; color: #eff6f8; position: sticky; top: 0; height: 100vh; z-index: 1;
}
.brand { display: flex; align-items: center; gap: 12px; padding: 0 10px; margin-bottom: 32px; }
.brand-mark { width: 38px; height: 38px; flex: none; fill: var(--primary); }
.brand-mark .mark-cut { stroke: #172b35; stroke-width: 2; }
.brand-name { display: block; font-size: 21px; font-weight: 800; letter-spacing: -.04em; color: #fff; }
.brand-kicker { display: block; margin-top: 3px; color: #9db3bc; font-size: 10px; letter-spacing: .08em; }
.palette-entry {
  display: flex; align-items: center; gap: 9px; margin: 0 8px 30px; padding: 10px 12px;
  border: 1px solid #3c5660; border-radius: 8px; background: #203943; color: #b8c8cd; font-size: 13px;
  cursor: pointer; font-family: inherit; transition: border-color var(--dur-expand) var(--ease), background var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease);
}
.palette-entry:hover { color: #fff; border-color: var(--primary); background: #294752; }
.palette-entry span { flex: 1; text-align: left; }
.palette-entry kbd { border: 1px solid #4a636c; border-radius: 4px; padding: 1px 5px; color: #a9bdc4; font-size: 10px; }
.nav { flex: 1; display: flex; flex-direction: column; gap: 26px; overflow-y: auto; }
.nav-group { display: flex; flex-direction: column; gap: 3px; }
.nav-title { padding: 0 13px 9px; color: #7e9aa4; font-size: 10px; letter-spacing: .18em; user-select: none; }
.nav-item {
  display: flex; align-items: center; gap: 11px; text-align: left; padding: 11px 13px; border-radius: 8px;
  border: none; background: none; color: #afc0c5; cursor: pointer; font-size: 14px; font-family: inherit;
  transition: color var(--dur-expand) var(--ease), background-color var(--dur-expand) var(--ease), transform var(--dur-expand) var(--ease);
}
.nav-item:hover { color: #fff; background: #203943; transform: translateX(3px); }
.nav-item.active { background: var(--primary); color: var(--primary-ink); font-weight: 800; }
.side-footer { display: flex; flex-direction: column; gap: 6px; margin-top: 18px; }
.user-card { display: flex; align-items: center; gap: 8px; padding: 11px 13px; border-top: 1px solid #344d57; color: #a9bdc4; font-size: 13px; }
.user-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.logout { color: #e6a2a0; }
.logout:hover { color: #ffd5d2; background: #3b3034; }
.content { flex: 1; padding: 42px clamp(22px, 4vw, 70px); overflow-y: auto; min-width: 0; }
.content:focus { outline: none; }
.console .card, .console .summary-card, .console .chart-card {
  background: var(--card); border: 1px solid var(--border-light); border-radius: 10px; box-shadow: var(--shadow-sm);
}
.console .card:hover, .console .summary-card:hover { border-color: var(--border); box-shadow: var(--shadow); }
.console .page > h2 { font-size: clamp(28px, 4vw, 42px); letter-spacing: -.05em; }
.console .btn-primary, .console .btn:not(.ghost):not(.danger) { border-radius: 8px; }
.console .sel, .console .amount-input { border-radius: 8px; }
.topbar, .bottom-nav { display: none; }
@media (max-width: 768px) {
  .console { display: block; min-height: 100dvh; }
  .sidebar { display: none; }
  .topbar {
    display: flex; align-items: center; justify-content: space-between; height: 56px; padding: 0 16px;
    position: sticky; top: 0; z-index: 100; background: color-mix(in srgb, var(--bg) 90%, transparent);
    backdrop-filter: blur(16px); border-bottom: 1px solid var(--border);
  }
  .topbar-title { font-size: 17px; font-weight: 800; letter-spacing: -.02em; }
  .topbar-actions { display: flex; align-items: center; gap: 4px; }
  .theme-btn { display: flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 10px; border: none; background: none; color: var(--muted); cursor: pointer; }
  .theme-btn:active { background: var(--surface-2); }
  .content { padding: 20px 16px calc(94px + env(safe-area-inset-bottom)); }
  .bottom-nav { display: flex; position: fixed; left: 12px; right: 12px; bottom: calc(10px + env(safe-area-inset-bottom)); height: 64px; border: 1px solid var(--border); border-radius: 16px; background: color-mix(in srgb, var(--card) 94%, transparent); box-shadow: 0 12px 28px rgba(27,35,38,.12); backdrop-filter: blur(16px); padding: 4px; z-index: 100; }
  .tab { position: relative; flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; border: none; border-radius: 11px; background: none; color: var(--muted); cursor: pointer; font-size: 11px; font-family: inherit; transition: color var(--dur-expand) var(--ease), background-color var(--dur-expand) var(--ease), transform var(--dur-expand) var(--ease); }
  .tab:hover { transform: translateY(-1px); }
  .tab.active { color: var(--primary-ink); background: var(--primary-soft); font-weight: 800; }
}
@media (max-width: 920px) and (min-width: 769px) {
  .sidebar { width: 220px; padding-inline: 14px; }
  .content { padding-inline: 28px; }
  .brand-kicker { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .skip-link, .nav-item, .palette-entry, .theme-btn, .tab { transition: none; }
}
</style>
