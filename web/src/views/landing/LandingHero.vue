<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { demoBudget, demoNetAssets } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'
import { countUp } from '../../utils/countUp'

const router = useRouter()

// 数据源隔离：落地页不 import src/stores/**、src/api/**. 已登录判断直接读 localStorage（与 http.ts 的 rkl_token 一致）。
function cta() { router.push(localStorage.getItem('rkl_token') ? '/console' : '/login') }
// 主标题点击同样跳「主页」：与 CTA 按 token 切换的逻辑保持一致（已登录→控制台，未登录→登录页）
function goHome() { cta() }
function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }) }

/* Hero 统计数字 countUp（W3c）：进入视口才起表（IntersectionObserver 触发一次）；
 * from 滑动 / rAF 取消 / reduced-motion 定格均由 countUp 内部处理。
 * 三个 key 全局唯一，重复进入落地页时从上次展示值续滑而非归零重涨。 */
const spentEl = ref<HTMLSpanElement | null>(null)
const netEl = ref<HTMLSpanElement | null>(null)
const daysEl = ref<HTMLSpanElement | null>(null)
const statsEl = ref<HTMLElement | null>(null)
let io: IntersectionObserver | undefined

// 演示数据：连续记账天数，与 demoInsight.highlights 的「连续 12 天打卡」镜像
const DEMO_STREAK_DAYS = 12

function runCountUps() {
  if (spentEl.value) countUp(spentEl.value, demoBudget.spentMinor, 'landing:hero:spent', formatMoney, 800)
  if (netEl.value) countUp(netEl.value, demoNetAssets, 'landing:hero:net', formatMoney, 800)
  if (daysEl.value) countUp(daysEl.value, DEMO_STREAK_DAYS, 'landing:hero:days', (n) => `+${Math.round(n)}`, 800)
}

onMounted(() => {
  // 兜底环境（如测试）无 IntersectionObserver：直接起表
  if (typeof IntersectionObserver === 'undefined' || !statsEl.value) { runCountUps(); return }
  io = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue
      runCountUps()
      io?.disconnect()
      io = undefined
    }
  }, { threshold: 0.3 })
  io.observe(statsEl.value)
})

onUnmounted(() => { io?.disconnect(); io = undefined })
</script>

<template>
  <section class="hero" v-reveal>
    <span class="hero-chip">个人记账 · 资产 · AI 洞察</span>
    <!-- 主标题可点：跳「主页」（已登录→控制台 / 未登录→登录页，与 CTA 同逻辑）；
         role="link" + 键盘回车/空格触发，键盘可达性不降级 -->
    <h1
      class="hero-title"
      role="link"
      tabindex="0"
      title="进入控制台"
      @click="goHome"
      @keydown.enter.prevent="goHome"
      @keydown.space.prevent="goHome"
    >把钱记在账本里，<br /><span class="grad">每一笔都值得被看见</span></h1>
    <p class="hero-sub">快速记账、收入支出一目了然；资产净额、月度预算、AI 月结复盘，一个控制台全搞定。</p>
    <div class="hero-cta">
      <button class="btn btn-primary" @click="cta">进入控制台</button>
      <button class="btn btn-ghost" @click="scrollTo('feature-bookkeeping')">查看演示 ↓</button>
    </div>
    <div ref="statsEl" class="hero-stats">
      <!-- 演示数据（静态 mock，无 API 拉取）：金额由 mock 派生，与 AiDemo 的 demoInsight「本月共支出 ¥3,520」一致。
           模板先渲染终值兜底（无 JS / reduced-motion 直读），进入视口后由 countUp 起表。 -->
      <div class="stat"><span ref="spentEl" class="stat-num amount">{{ formatMoney(demoBudget.spentMinor) }}</span><span class="stat-label">本月记账</span></div>
      <div class="stat"><span ref="netEl" class="stat-num amount">{{ formatMoney(demoNetAssets) }}</span><span class="stat-label">净资</span></div>
      <div class="stat"><span ref="daysEl" class="stat-num">+{{ DEMO_STREAK_DAYS }}</span><span class="stat-label">连续记账天数</span></div>
    </div>
  </section>
</template>

<style scoped>

.hero { max-width: 1080px; margin: 0 auto; padding: clamp(78px, 12vw, 150px) 24px 86px; text-align: left; position: relative; }
.hero::before { content: '01 / 记一笔'; position: absolute; top: 44px; left: 24px; color: var(--muted); font-size: 11px; letter-spacing: .16em; }
.hero-chip { display: inline-block; padding: 7px 12px; border: 1px solid var(--border); border-radius: 999px; color: var(--primary); font-size: 12px; font-weight: 700; letter-spacing: .05em; margin-bottom: 24px; }
.hero-title { max-width: 850px; font-size: clamp(45px, 8vw, 94px); line-height: .98; font-weight: 800; letter-spacing: -0.055em; margin: 0 0 26px; cursor: pointer; transition: transform var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease); }
.hero-title:hover { transform: translateX(5px); color: var(--primary-ink); }
.hero-title .grad { color: var(--primary-ink); }
.hero-sub { font-size: 17px; color: var(--muted); max-width: 56ch; margin: 0 0 34px; line-height: 1.7; }
.hero-cta { display: flex; gap: 12px; justify-content: flex-start; flex-wrap: wrap; margin-bottom: 64px; }
.hero-stats { display: grid; grid-template-columns: repeat(3, minmax(120px, 220px)); gap: 0; border-top: 1px solid var(--border); max-width: 680px; }
.stat { display: flex; flex-direction: column; gap: 7px; padding: 18px 24px 0 0; margin-right: 24px; border-right: 1px solid var(--border); }
.stat:last-child { border-right: 0; }
.stat-num { font-size: clamp(24px, 3vw, 34px); font-weight: 800; letter-spacing: -.04em; }
.stat-label { font-size: 12px; color: var(--muted); }
@media (max-width: 640px) { .hero { padding: 74px 16px 56px; } .hero::before { left: 16px; } .hero-title { font-size: clamp(42px, 13vw, 66px); } .hero-stats { grid-template-columns: 1fr 1fr; gap: 16px; } .stat { padding-top: 14px; margin-right: 0; } .stat:nth-child(2) { border-right: 0; } .stat:last-child { grid-column: 1 / -1; border-top: 1px solid var(--border); padding-top: 14px; } }

</style>
