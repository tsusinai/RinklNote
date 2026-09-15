<script setup lang="ts">
import { computed } from 'vue'
import { ICONS, type IconName } from './icons'

/* 内联 SVG 图标渲染器：stroke 继承 currentColor，跟随文字色随主题变化 */
const props = withDefaults(defineProps<{ name: IconName; size?: number }>(), { size: 20 })
const markup = computed(() => ICONS[props.name] ?? '')
</script>

<template>
  <!-- 图标片段均为 icons.ts 内静态常量，无用户输入，v-html 安全 -->
  <svg
    :width="size" :height="size" viewBox="0 0 24 24"
    fill="none" stroke="currentColor" stroke-width="2"
    stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true" v-html="markup"
  />
</template>
