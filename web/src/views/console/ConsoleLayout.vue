<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const store = useAuthStore()

const tabs = [
  { name: 'bookkeeping', label: '记账' },
  { name: 'bills', label: '账单' },
  { name: 'charts', label: '图表' },
  { name: 'assets', label: '资产' },
  { name: 'me', label: '我的' },
  { name: 'settings', label: '设置' },
]
function go(name: string) { router.push({ name }) }
</script>

<template>
  <div class="console">
    <aside class="sidebar">
      <h1>RinklNote</h1>
      <nav>
        <button v-for="t in tabs" :key="t.name" :class="{ active: $route.name === t.name }" @click="go(t.name)">{{ t.label }}</button>
      </nav>
      <button class="logout" @click="store.logout(); router.push('/login')">退出</button>
    </aside>
    <main class="content"><RouterView /></main>
    <nav class="mobile-nav">
      <button v-for="t in tabs.slice(0, 5)" :key="'m' + t.name" :class="{ active: $route.name === t.name }" @click="go(t.name)">{{ t.label }}</button>
    </nav>
  </div>
</template>

<style scoped>
.console { display: flex; min-height: 100vh; background: var(--bg); }
.sidebar { width: 200px; padding: 20px 12px; display: flex; flex-direction: column; gap: 8px; border-right: 1px solid var(--border-light); }
.sidebar h1 { font-size: 20px; margin: 0 0 16px; }
.sidebar nav { display: flex; flex-direction: column; gap: 4px; }
.sidebar nav button, .logout { text-align: left; padding: 10px 12px; border-radius: 12px; border: none; background: none; color: var(--muted); cursor: pointer; font-size: 14px; font-family: inherit; }
.sidebar nav button.active { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.logout { margin-top: auto; color: var(--expense); }
.content { flex: 1; padding: 20px 24px; overflow-y: auto; }
.mobile-nav { display: none; }
@media (max-width: 768px) {
  .sidebar { display: none; }
  .mobile-nav { display: flex; position: fixed; left: 0; right: 0; bottom: 0; height: 60px; background: var(--card); border-top: 1px solid var(--border-light); padding-bottom: env(safe-area-inset-bottom); z-index: 100; }
  .mobile-nav button { flex: 1; border: none; background: none; color: var(--muted); font-size: 11px; }
  .mobile-nav button.active { color: var(--primary); font-weight: 600; }
  .content { padding: 16px 14px 84px; }
}
</style>
