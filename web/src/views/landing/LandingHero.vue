<script setup lang="ts">
import { useRouter } from 'vue-router'
import { demoBudget, demoNetAssets } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'

const router = useRouter()

// 数据源隔离：落地页不 import src/stores/**、src/api/**. 已登录判断直接读 localStorage（与 http.ts 的 rkl_token 一致）。
function cta() { router.push(localStorage.getItem('rkl_token') ? '/console' : '/login') }
function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }) }
</script>

<template>
  <section class="hero" v-reveal>
    <span class="hero-chip">个人记账 · 资产 · AI 洞察</span>
    <h1 class="hero-title">把钱记在账本里，<br /><span class="grad">每一笔都值得被看见</span></h1>
    <p class="hero-sub">快速记账、收入支出一目了然；资产净额、月度预算、AI 月结复盘，一个控制台全搞定。</p>
    <div class="hero-cta">
      <button class="btn btn-primary" @click="cta">进入控制台</button>
      <button class="btn btn-ghost" @click="scrollTo('feature-bookkeeping')">查看演示 ↓</button>
    </div>
    <div class="hero-stats">
      <!-- 金额由 mock 派生，与 AiDemo 的 demoInsight「本月共支出 ¥3,520」一致；天数 +12 镜像 demoInsight.highlights 的「连续 12 天打卡」。 -->
      <div class="stat"><span class="stat-num amount">{{ formatMoney(demoBudget.spentMinor) }}</span><span class="stat-label">本月记账</span></div>
      <div class="stat"><span class="stat-num amount">{{ formatMoney(demoNetAssets) }}</span><span class="stat-label">净资</span></div>
      <div class="stat"><span class="stat-num">+12</span><span class="stat-label">连续记账天数</span></div>
    </div>
  </section>
</template>

<style scoped>
.hero { max-width: 1080px; margin: 0 auto; padding: 72px 24px 40px; text-align: center; }
.hero-chip { display: inline-block; padding: 6px 14px; border-radius: 999px; background: var(--primary-soft); color: var(--primary); font-size: 13px; font-weight: 600; margin-bottom: 24px; }
.hero-title { font-size: clamp(34px, 6vw, 60px); line-height: 1.1; font-weight: 800; letter-spacing: -0.02em; margin: 0 0 20px; }
.hero-title .grad { background: linear-gradient(120deg, var(--income), var(--primary) 60%, var(--expense)); -webkit-background-clip: text; background-clip: text; color: transparent; }
.hero-sub { font-size: 17px; color: var(--muted); max-width: 620px; margin: 0 auto 32px; line-height: 1.6; }
.hero-cta { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; margin-bottom: 56px; }
.btn { padding: 13px 22px; border-radius: 12px; border: none; font-size: 15px; font-weight: 600; cursor: pointer; transition: transform .12s var(--ease), box-shadow .12s var(--ease); }
.btn:active { transform: scale(.97); }
.btn-primary { background: var(--primary); color: #0b2b44; box-shadow: 0 4px 18px rgba(126,193,252,.35); }
.btn-ghost { background: transparent; color: var(--text); border: 1px solid var(--border); }
.hero-stats { display: flex; justify-content: center; gap: clamp(16px, 4vw, 48px); flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; gap: 6px; }
.stat-num { font-size: 26px; font-weight: 700; }
.stat-label { font-size: 13px; color: var(--muted); }
@media (max-width: 640px) { .hero { padding: 48px 16px 32px; } .hero-title { font-size: clamp(30px, 8vw, 40px); } }
</style>
