<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useThemeStore } from '../../stores/theme'
import { useDataStore } from '../../stores/data'
import Icon from '../../components/ui/Icon.vue'
import type { IconName } from '../../components/ui/icons'
import { pageTransition } from '../../router/transition'
import avatarUrl from '../../assets/xiaopan-avatar.png'

/* 布局壳：桌面=侧栏（品牌/分组导航/用户卡/退出），移动=顶栏(标题+主题切换)+底栏 5 tab。
 * 「设置」不占移动端底栏，入口在我的页与桌面侧栏；颜色全部走 CSS 变量。 */
const route = useRoute()
const router = useRouter()
const store = useAuthStore()
const theme = useThemeStore()
const data = useDataStore()

interface NavItem { name: string; label: string; icon: IconName }
const groups: { title: string; items: NavItem[] }[] = [
  { title: '记账', items: [
    { name: 'bookkeeping', label: '记账', icon: 'book' },
    { name: 'bills', label: '账单', icon: 'receipt' },
    { name: 'charts', label: '图表', icon: 'chart' },
  ] },
  { title: '资产', items: [{ name: 'assets', label: '资产', icon: 'wallet' }] },
  { title: '我的', items: [
    { name: 'me', label: '我的', icon: 'user' },
    { name: 'settings', label: '设置', icon: 'settings' },
  ] },
]
/* 移动端底栏固定 5 tab（不再把设置 slice 塞进底栏） */
const mobileTabs: NavItem[] = groups.flatMap((g) => g.items).filter((it) => it.name !== 'settings')

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
  data.bills = []; data.cats = []; data.accts = []; data.keywords = []; data.lastSync = 0
  router.push('/login')
}
</script>

<template>
  <div class="console">
    <!-- 桌面侧栏 -->
    <aside class="sidebar">
      <div class="brand">
        <img :src="avatarUrl" alt="小盘头像" class="brand-avatar" />
        <span class="brand-name">记一笔</span>
      </div>
      <nav class="nav">
        <div v-for="g in groups" :key="g.title" class="nav-group">
          <div class="nav-title">{{ g.title }}</div>
          <button
            v-for="it in g.items" :key="it.name"
            class="nav-item" :class="{ active: route.name === it.name }"
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

    <!-- 移动端顶栏：页面标题 + 主题切换 -->
    <header class="topbar">
      <span class="topbar-title">{{ pageTitle }}</span>
      <button class="theme-btn" :aria-label="isDark ? '切换为亮色' : '切换为暗色'" @click="toggleTheme">
        <Icon :name="isDark ? 'sun' : 'moon'" :size="18" />
      </button>
    </header>

    <!-- 内层路由：控制台内部按 tab 序号判方向转场（page-forward/back），进出控制台由 App 层 fade -->
    <main class="content">
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
        @click="go(t.name)"
      >
        <Icon :name="t.icon" :size="20" />
        <span>{{ t.label }}</span>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.console { display: flex; min-height: 100vh; background: var(--bg); }

/* ── 桌面侧栏 ── */
.sidebar {
  width: 208px; flex-shrink: 0;
  padding: 20px 12px 16px;
  display: flex; flex-direction: column;
  border-right: 1px solid var(--border-light);
  position: sticky; top: 0; height: 100vh;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 0 8px; margin-bottom: 20px; }
.brand-avatar { width: 34px; height: 34px; border-radius: 50%; object-fit: cover; }
.brand-name { font-size: 18px; font-weight: 700; color: var(--text); }
.nav { flex: 1; display: flex; flex-direction: column; gap: 14px; overflow-y: auto; }
.nav-group { display: flex; flex-direction: column; gap: 2px; }
.nav-title { font-size: 12px; color: var(--muted); padding: 0 12px 4px; user-select: none; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  text-align: left; padding: 10px 12px; border-radius: 12px;
  border: none; background: none; color: var(--muted);
  cursor: pointer; font-size: 14px; font-family: inherit;
  transition: color var(--dur-fast) var(--ease), background-color var(--dur-fast) var(--ease);
}
.nav-item:hover { color: var(--text); background: var(--surface-2); }
.nav-item.active { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.side-footer { display: flex; flex-direction: column; gap: 6px; margin-top: 12px; }
.user-card {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px; border-radius: 12px;
  background: var(--surface-2); color: var(--muted); font-size: 13px;
}
.user-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.logout { color: var(--expense); }
.logout:hover { color: var(--expense); background: var(--surface-2); }

/* 内容区 */
.content { flex: 1; padding: 20px 24px; overflow-y: auto; min-width: 0; }

/* 顶栏 / 底栏：桌面不显示 */
.topbar, .bottom-nav { display: none; }

@media (max-width: 768px) {
  .console { display: block; }
  .sidebar { display: none; }
  /* 顶栏 46px：页面标题 + 主题切换 */
  .topbar {
    display: flex; align-items: center; justify-content: space-between;
    height: 46px; padding: 0 14px;
    position: sticky; top: 0; z-index: 100;
    background: var(--bg); border-bottom: 1px solid var(--border-light);
  }
  .topbar-title { font-size: 16px; font-weight: 600; color: var(--text); }
  .theme-btn {
    display: flex; align-items: center; justify-content: center;
    width: 34px; height: 34px; border-radius: 50%;
    border: none; background: none; color: var(--muted); cursor: pointer;
  }
  .content { padding: 14px 14px 86px; }
  /* 底栏固定 5 tab：图标 + 文字 */
  .bottom-nav {
    display: flex;
    position: fixed; left: 0; right: 0; bottom: 0; height: 56px;
    background: var(--card); border-top: 1px solid var(--border-light);
    padding-bottom: env(safe-area-inset-bottom); z-index: 100;
  }
  .tab {
    flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
    border: none; background: none; color: var(--muted); cursor: pointer; font-size: 11px; font-family: inherit;
    transition: color var(--dur-fast) var(--ease);
  }
  .tab.active { color: var(--primary); font-weight: 600; }
}
</style>
