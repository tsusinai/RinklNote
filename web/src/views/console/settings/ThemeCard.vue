<script setup lang="ts">
import { useThemeStore } from '../../../stores/theme'
import type { ThemeMode } from '../../../stores/theme'
import Card from '../../../components/ui/Card.vue'
const theme = useThemeStore()
const modes: { v: ThemeMode; label: string }[] = [
  { v: 'system', label: '跟随系统' },
  { v: 'light', label: '浅色' },
  { v: 'dark', label: '深色' },
]
</script>

<template>
  <Card title="主题">
    <div class="toggle">
      <button v-for="m in modes" :key="m.v" :class="['toggle-btn', { on: theme.theme === m.v }]" @click="theme.set(m.v)">{{ m.label }}</button>
    </div>
  </Card>
</template>

<style scoped>
/* 仅保留本卡特有的切换按钮样式；卡片/标题走全局 .card/.card-title（Card 基础件） */
.toggle { display: flex; gap: 8px; }
.toggle-btn {
  flex: 1; padding: 10px; border-radius: 12px; border: 1px solid var(--border); background: none;
  color: var(--muted); font-size: 14px; cursor: pointer; font-family: inherit;
  transition: background var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease);
}
.toggle-btn.on { background: var(--primary); color: var(--on-primary); font-weight: 600; border-color: transparent; }
</style>
