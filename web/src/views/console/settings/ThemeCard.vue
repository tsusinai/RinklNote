<script setup lang="ts">
import { useThemeStore } from '../../../stores/theme'
import type { ThemeMode } from '../../../stores/theme'
const theme = useThemeStore()
const modes: { v: ThemeMode; label: string }[] = [
  { v: 'system', label: '跟随系统' },
  { v: 'light', label: '浅色' },
  { v: 'dark', label: '深色' },
]
</script>

<template>
  <div class="card">
    <div class="card-title">主题</div>
    <div class="toggle">
      <button v-for="m in modes" :key="m.v" :class="['toggle-btn', { on: theme.theme === m.v }]" @click="theme.set(m.v)">{{ m.label }}</button>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.toggle { display: flex; gap: 8px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 12px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on { background: var(--primary); color: var(--on-primary); font-weight: 600; border-color: transparent; }
</style>
