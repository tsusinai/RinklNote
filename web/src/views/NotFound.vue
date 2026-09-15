<script setup lang="ts">
import { useRouter } from 'vue-router'
import Btn from '../components/ui/Btn.vue'

/* 404 兜底页：大字 404 + 主蓝「回到记账」按钮（消费 W1 Btn 基础件） */
const router = useRouter()
</script>

<template>
  <div class="nf">
    <p class="code">404</p>
    <p class="hint">页面走丢了，这里什么都没有</p>
    <Btn @click="router.push('/console')">回到记账</Btn>
  </div>
</template>

<style scoped>
.nf {
  min-height: 100vh;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 4px; padding: 24px; background: var(--bg);
}
.code { font-size: 72px; font-weight: 700; line-height: 1.1; margin: 0; color: var(--primary); }
.hint { color: var(--muted); margin: 0 0 18px; font-size: 14px; }
</style>
