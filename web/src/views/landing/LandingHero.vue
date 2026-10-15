<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { demoBudget, demoNetAssets, demoInsight } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'
import { countUp } from '../../utils/countUp'

const router = useRouter()
const motionPaused = ref(false)
const loggedIn = () => !!localStorage.getItem('rkl_token')
function cta() { router.push(loggedIn() ? '/console' : '/login') }
function goHome() { cta() }
function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }) }
function toggleMotion() {
  motionPaused.value = !motionPaused.value
  document.documentElement.toggleAttribute('data-motion-paused', motionPaused.value)
}

const spentEl = ref<HTMLSpanElement | null>(null)
const netEl = ref<HTMLSpanElement | null>(null)
const daysEl = ref<HTMLSpanElement | null>(null)
const statsEl = ref<HTMLElement | null>(null)
let io: IntersectionObserver | undefined
const DEMO_STREAK_DAYS = 12

function runCountUps() {
  if (spentEl.value) countUp(spentEl.value, demoBudget.spentMinor, 'landing:hero:spent', formatMoney, 800)
  if (netEl.value) countUp(netEl.value, demoNetAssets, 'landing:hero:net', formatMoney, 800)
  if (daysEl.value) countUp(daysEl.value, DEMO_STREAK_DAYS, 'landing:hero:days', (n) => `+${Math.round(n)}`, 800)
}

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined' || !statsEl.value) { runCountUps(); return }
  io = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) {
      runCountUps()
      io?.disconnect()
      io = undefined
    }
  }, { threshold: 0.3 })
  io.observe(statsEl.value)
})

onUnmounted(() => {
  io?.disconnect()
  io = undefined
  document.documentElement.removeAttribute('data-motion-paused')
})
</script>

<template>
  <section class="hero" v-reveal aria-labelledby="landing-title">
    <div class="hero-copy">
      <div class="hero-kicker"><span class="kicker-line"></span><span>RINKLNOTE / 个人财务工作台</span></div>
      <h1 id="landing-title" class="hero-title" role="link" tabindex="0" title="进入工作台" @click="goHome" @keydown.enter.prevent="goHome" @keydown.space.prevent="goHome">
        让每一笔，<br /><span>都成为更好的决定。</span>
      </h1>
      <p class="hero-sub">从随手记下第一笔，到看懂一个月的生活。RinklNote 把记账、资产与 AI 复盘放进一张清晰的工作台。</p>
      <div class="hero-cta">
        <button class="btn btn-primary pressable" @click="cta">{{ loggedIn() ? '进入工作台' : '开始记账' }} <span aria-hidden="true">↗</span></button>
        <button class="btn btn-ghost" @click="scrollTo('feature-bookkeeping')">浏览功能 <span aria-hidden="true">↓</span></button>
      </div>
      <div ref="statsEl" class="hero-stats" aria-label="产品演示数据" aria-live="polite">
        <div class="stat"><span ref="spentEl" class="stat-num amount">{{ formatMoney(demoBudget.spentMinor) }}</span><span class="stat-label">本月记账</span></div>
        <div class="stat"><span ref="netEl" class="stat-num amount">{{ formatMoney(demoNetAssets) }}</span><span class="stat-label">当前净资</span></div>
        <div class="stat"><span ref="daysEl" class="stat-num">+{{ DEMO_STREAK_DAYS }}</span><span class="stat-label">连续记账天数</span></div>
      </div>
    </div>

    <div class="hero-board" v-reveal="120" aria-label="RinklNote 工作台预览">
      <div class="board-topline"><span>本月 · 2026.10</span><button class="pause-button" type="button" :aria-pressed="motionPaused" @click="toggleMotion"><span class="pause-icon" aria-hidden="true">{{ motionPaused ? '▶' : 'Ⅱ' }}</span>{{ motionPaused ? '恢复动效' : '暂停动效' }}</button></div>
      <div class="board-head"><span class="board-label">月度结余</span><span class="board-status"><i></i> 状态良好</span></div>
      <div class="board-balance amount">{{ formatMoney(demoBudget.totalMinor - demoBudget.spentMinor) }}</div>
      <div class="board-meter"><span :style="{ width: `${Math.round((demoBudget.spentMinor / demoBudget.totalMinor) * 100)}%` }"></span></div>
      <div class="board-meta"><span>已用 {{ Math.round((demoBudget.spentMinor / demoBudget.totalMinor) * 100) }}%</span><span>预算 {{ formatMoney(demoBudget.totalMinor) }}</span></div>
      <div class="board-divider"></div>
      <div class="board-section-title"><span>最近动态</span><span>实时</span></div>
      <div class="board-feed">
        <div class="feed-row"><span class="feed-mark blue">记</span><span><b>三餐</b><small>今天 12:30 · 午餐</small></span><strong class="amount">-¥25.00</strong></div>
        <div class="feed-row"><span class="feed-mark green">入</span><span><b>工资到账</b><small>昨天 · 工资</small></span><strong class="amount income">+¥8,500.00</strong></div>
        <div class="feed-row"><span class="feed-mark amber">AI</span><span><b>月结复盘</b><small>{{ demoInsight.highlights[0] }}</small></span><strong class="feed-arrow" aria-hidden="true">↗</strong></div>
      </div>
      <div class="board-caption">记得更轻松，看得更明白。</div>
    </div>
  </section>
</template>

<style scoped>
.hero { display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(320px, .75fr); align-items: center; gap: clamp(36px, 8vw, 120px); max-width: 1240px; margin: 0 auto; padding: clamp(78px, 11vw, 142px) clamp(20px, 5vw, 72px) clamp(76px, 10vw, 128px); position: relative; }
.hero::before { content: '01 / 开始记录'; position: absolute; top: clamp(26px, 4vw, 52px); left: clamp(20px, 5vw, 72px); color: var(--muted); font: 11px/1 "Noto Sans SC", sans-serif; letter-spacing: .16em; }
.hero-copy { max-width: 700px; }
.hero-kicker { display: inline-flex; align-items: center; gap: 10px; color: var(--primary-ink); font: 11px/1 "Noto Sans SC", sans-serif; letter-spacing: .13em; margin-bottom: 24px; }
.kicker-line { width: 28px; height: 2px; background: var(--primary); }
.hero-title { max-width: 720px; margin: 0 0 26px; font-size: clamp(46px, 7.2vw, 92px); line-height: .98; font-weight: 800; letter-spacing: -.06em; cursor: pointer; transition: transform var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease); }
.hero-title:hover { transform: translateX(5px); color: var(--primary-ink); }
.hero-title span { color: var(--primary-ink); }
.hero-sub { max-width: 52ch; margin: 0 0 34px; color: var(--muted); font-size: 16px; line-height: 1.8; }
.hero-cta { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: clamp(48px, 7vw, 76px); }
.hero-cta .btn { min-height: 44px; padding-inline: 18px; }
.hero-stats { display: grid; grid-template-columns: repeat(3, minmax(100px, 160px)); border-top: 1px solid var(--border); }
.stat { display: flex; flex-direction: column; gap: 8px; min-height: 78px; padding: 16px 18px 0 0; margin-right: 18px; border-right: 1px solid var(--border); }
.stat:last-child { border-right: none; margin-right: 0; }
.stat-num { font-size: clamp(24px, 3vw, 34px); font-weight: 800; letter-spacing: -.04em; }
.stat-label { color: var(--muted); font-size: 12px; }
.hero-board { position: relative; padding: 22px clamp(18px, 3vw, 28px) 24px; border: 1px solid color-mix(in srgb, var(--primary-ink) 32%, var(--border)); border-radius: 20px; background: color-mix(in srgb, var(--card) 86%, transparent); box-shadow: 0 22px 70px color-mix(in srgb, var(--primary-ink) 14%, transparent), inset 0 1px 0 color-mix(in srgb, white 50%, transparent); transform: rotate(1.2deg); transition: transform var(--dur-expand) var(--ease); }
.hero-board:hover { transform: rotate(0deg) translateY(-4px); }
.hero-board::before { content: ''; position: absolute; top: 14px; right: 18px; width: 74px; height: 74px; border-radius: 50%; background: color-mix(in srgb, var(--primary) 18%, transparent); filter: blur(20px); pointer-events: none; }
.board-topline, .board-head, .board-meta, .board-section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.board-topline { margin-bottom: 28px; color: var(--muted); font: 11px/1 "Noto Sans SC", sans-serif; letter-spacing: .07em; }
.pause-button { display: inline-flex; align-items: center; gap: 5px; padding: 0; border: 0; color: var(--muted); background: transparent; font: inherit; cursor: pointer; }
.pause-button:hover { color: var(--text); }
.pause-icon { display: grid; place-items: center; width: 17px; height: 17px; border: 1px solid currentColor; border-radius: 50%; font-size: 8px; }
.board-head { margin-bottom: 8px; }
.board-label { color: var(--muted); font-size: 13px; }
.board-status { display: inline-flex; align-items: center; gap: 6px; color: var(--income); font-size: 11px; }
.board-status i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; animation: status-pulse 2.4s ease-in-out infinite; }
.board-balance { margin-bottom: 18px; font-size: clamp(34px, 5vw, 48px); font-weight: 800; letter-spacing: -.05em; }
.board-meter { height: 7px; overflow: hidden; border-radius: 999px; background: var(--surface-2); }
.board-meter span { display: block; height: 100%; border-radius: inherit; background: var(--primary); transform-origin: left center; animation: meter-in 1.2s var(--ease) both; }
.board-meta { margin-top: 8px; color: var(--muted); font-size: 11px; }
.board-divider { height: 1px; margin: 24px 0 18px; background: var(--border-light); }
.board-section-title { margin-bottom: 13px; color: var(--text); font-size: 12px; font-weight: 700; }
.board-section-title > span:last-child { color: var(--income); font-size: 11px; font-weight: 500; }
.board-feed { display: flex; flex-direction: column; gap: 12px; }
.feed-row { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 10px; min-width: 0; }
.feed-mark { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 9px; color: var(--on-primary); background: var(--primary); font-size: 11px; font-weight: 800; }
.feed-mark.green { background: var(--income); color: var(--on-income); }
.feed-mark.amber { color: var(--text); background: color-mix(in srgb, var(--primary) 30%, var(--card)); }
.feed-row span:nth-child(2) { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.feed-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.feed-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 10px; }
.feed-row strong { color: var(--expense); font-size: 12px; }
.feed-row strong.income { color: var(--income); }
.feed-arrow { color: var(--primary-ink) !important; font-size: 16px !important; }
.board-caption { margin-top: 22px; padding-top: 12px; border-top: 1px solid var(--border-light); color: var(--muted); font-size: 11px; }
@keyframes status-pulse { 0%, 100% { opacity: .4; transform: scale(.8); } 50% { opacity: 1; transform: scale(1); } }
@keyframes meter-in { from { transform: scaleX(0); } to { transform: scaleX(1); } }
@media (max-width: 900px) { .hero { grid-template-columns: 1fr; gap: 48px; padding-top: 112px; } .hero-copy { max-width: 720px; } .hero-board { max-width: 460px; width: 100%; margin-left: auto; } }
@media (max-width: 560px) { .hero { padding: 92px 16px 70px; gap: 34px; } .hero::before { left: 16px; } .hero-title { font-size: clamp(42px, 13.5vw, 66px); } .hero-sub { font-size: 15px; } .hero-stats { grid-template-columns: 1fr 1fr; gap: 0 12px; } .stat { margin-right: 0; padding-right: 12px; } .stat:nth-child(2) { border-right: 0; } .stat:last-child { grid-column: 1 / -1; border-top: 1px solid var(--border); border-right: 0; padding-top: 13px; margin-top: 12px; } .hero-board { padding: 18px 16px 20px; transform: none; } .hero-board:hover { transform: translateY(-3px); } }
@media (prefers-reduced-motion: reduce) { .hero-board, .hero-board:hover { transform: none; } }
</style>
