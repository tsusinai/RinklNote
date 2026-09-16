<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalToast from './components/GlobalToast.vue'
import { useThemeStore } from './stores/theme'

// Plan A 遗留：theme.init() 无调用点。此处补齐 —— 挂载时应用 data-theme（data-theme CSS 变量本任务才定义）。
useThemeStore().init()
</script>

<template>
  <!-- 顶层转场：Landing↔Login / 进出控制台 / 404 之间纯 fade。
       key 取一级 matched path —— 控制台子路由切换时布局壳不重挂载，
       方向转场由 ConsoleLayout 的内层 RouterView 负责（见 transition.ts）。 -->
  <RouterView v-slot="{ Component, route }">
    <Transition name="page-fade" mode="out-in">
      <component :is="Component" :key="route.matched[0]?.path ?? route.path" />
    </Transition>
  </RouterView>
  <GlobalToast />
</template>
