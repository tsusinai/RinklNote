<script setup lang="ts">
import Icon from './Icon.vue'
import type { IconName } from './icons'

/* 空状态基础件：图标 + 主文案 + 可选辅助说明，统一各页零散的「暂无数据」样式。 */
withDefaults(defineProps<{
  icon?: IconName
  text: string
  hint?: string
}>(), {})
</script>

<template>
  <div class="ui-empty">
    <Icon v-if="icon" :name="icon" :size="28" />
    <p class="text">{{ text }}</p>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.ui-empty {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 32px 16px; color: var(--muted); text-align: center;
}
.text { margin: 0; font-size: 14px; }
.hint { margin: 0; font-size: 12px; opacity: .8; }
</style>
