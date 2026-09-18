<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import Icon from '../../components/ui/Icon.vue'

/* 管理端布局：深色侧栏变体 + 「运维」徽标（规格红线：管理端零常驻动效）。
 * 与 ConsoleLayout 解耦：不共享底栏/主题切换，返回用户控制台走显式链接。 */
const route = useRoute()

const navItems = [
  { to: '/admin', label: '运维大盘', icon: 'chart', exact: true },
  { to: '/admin/users', label: '用户管理', icon: 'user', exact: false },
  { to: '/admin/bots', label: '机器人运维', icon: 'bot', exact: false },
  { to: '/admin/push-logs', label: '推送历史', icon: 'receipt', exact: false },
] as const

function isActive(item: { to: string; exact: boolean }): boolean {
  return item.exact ? route.path === item.to : route.path.startsWith(item.to)
}

const currentTitle = navItems.find(isActive)?.label ?? '运维'
</script>

<template>
  <div class="admin-root">
    <!-- 桌面侧栏：固定深色（管理端身份色，不随主题反转） -->
    <aside class="admin-side">
      <div class="admin-brand">
        <span class="admin-badge">运维</span>
        <span class="admin-brand-name">RinklNote 管理端</span>
      </div>
      <nav class="admin-nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="admin-nav-link"
          :class="{ on: isActive(item) }"
        >
          <Icon :name="item.icon" :size="16" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="admin-side-footer">
        <RouterLink to="/console" class="admin-back">← 返回用户控制台</RouterLink>
      </div>
    </aside>

    <!-- 主区：移动端顶栏 + 页面 -->
    <div class="admin-main">
      <header class="admin-topbar">
        <span class="admin-badge">运维</span>
        <strong>{{ currentTitle }}</strong>
        <RouterLink to="/console" class="admin-back-m">返回控制台</RouterLink>
      </header>
      <main class="admin-page">
        <RouterView v-slot="{ Component }">
          <Transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-root { display: flex; min-height: 100vh; background: var(--bg, #f5f7fa); }
.admin-side {
  width: 200px; flex-shrink: 0; display: flex; flex-direction: column;
  background: #10293b; color: #e8eef4; padding: 20px 14px;
}
.admin-brand { display: flex; align-items: center; gap: 8px; padding: 4px 8px 16px; }
.admin-brand-name { font-size: 13px; font-weight: 600; opacity: .92; }
.admin-badge {
  font-size: 11px; line-height: 1; padding: 3px 6px; border-radius: 4px;
  background: #f97d1d; color: #fff; font-weight: 600; flex-shrink: 0;
}
.admin-nav { display: flex; flex-direction: column; gap: 2px; }
.admin-nav-link {
  display: flex; align-items: center; gap: 8px; padding: 10px 10px;
  border-radius: 8px; color: #c3d0dc; font-size: 13px; text-decoration: none;
  transition: background var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease);
}
.admin-nav-link:hover { background: rgba(255, 255, 255, .06); color: #fff; }
.admin-nav-link.on { background: color-mix(in srgb, var(--primary) 18%, transparent); color: var(--primary); font-weight: 600; }
.admin-side-footer { margin-top: auto; padding: 8px; }
.admin-back { color: #8fa3b5; font-size: 12px; text-decoration: none; }
.admin-back:hover { color: #c3d0dc; }

.admin-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.admin-topbar {
  display: none; align-items: center; gap: 8px; padding: 12px 14px;
  background: #10293b; color: #e8eef4;
}
.admin-back-m { margin-left: auto; color: #8fa3b5; font-size: 12px; text-decoration: none; }
.admin-page { flex: 1; padding: 16px; max-width: 1080px; width: 100%; margin: 0 auto; }

@media (max-width: 768px) {
  .admin-side { display: none; }
  .admin-topbar { display: flex; }
  .admin-page { padding: 12px; }
}
</style>
