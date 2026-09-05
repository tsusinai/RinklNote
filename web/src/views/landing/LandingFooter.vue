<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <footer class="landing-footer">
    <div class="footer-brand">
      <strong>RinklNote</strong>
      <span class="footer-sub">记账 · 资产 · AI 洞察</span>
    </div>
    <nav class="footer-links">
      <button class="link" @click="router.push('/login')">后台登录</button>
      <span class="dot">·</span>
      <span class="muted">© 2026 RinklNote</span>
    </nav>
  </footer>
</template>

<style scoped>
.landing-footer { border-top: 1px solid var(--border-light); padding: 28px 24px 40px; display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; max-width: 1080px; margin: 40px auto 0; }
.footer-brand { display: flex; align-items: baseline; gap: 12px; }
.footer-sub { font-size: 13px; color: var(--muted); }
.footer-links { display: flex; align-items: center; gap: 8px; }
.link { background: none; border: none; color: var(--muted); cursor: pointer; font-size: 14px; }
.link:hover { color: var(--primary); }
.dot { color: var(--border); }
.muted { color: var(--muted); font-size: 14px; }
</style>
