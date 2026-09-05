<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalToast from './components/GlobalToast.vue'
import { useThemeStore } from './stores/theme'

// Plan A 遗留：theme.init() 无调用点。此处补齐 —— 挂载时应用 data-theme（data-theme CSS 变量本任务才定义）。
useThemeStore().init()
</script>

<template>
  <RouterView />
  <GlobalToast />
</template>
