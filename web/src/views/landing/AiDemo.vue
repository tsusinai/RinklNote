<script setup lang="ts">
import { demoBudget, demoInsight } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'

const pct = Math.round((demoBudget.spentMinor / demoBudget.totalMinor) * 100)
const C = 2 * Math.PI * 40 // ring circumference (r=40)
const dash = (C * pct) / 100
</script>

<template>
  <div class="feature-grid">
    <div class="feature-copy" v-reveal>
      <span class="eyebrow">AI · 语音 · 预算</span>
      <h2>AI 帮你复盘，预算提醒你别超支</h2>
      <p class="lead">月结自动生成总结与建议；语音说出「午餐 25 块」就能记一笔；预算进度环时刻提醒。</p>
      <ul class="points">
        <li>月结 / 异常 / 习惯 AI 洞察一键看完</li>
        <li>语音识别记账，动口不动手</li>
        <li>月度预算进度，超支预警</li>
      </ul>
    </div>

    <div class="demo-phone" v-reveal>
      <div class="phone-card">
        <div class="phone-title"><span>AI 记账总结</span><span class="pill ai">{{ demoInsight.monthLabel }}</span></div>
        <div class="insight-card">
          <p class="insight-summary">{{ demoInsight.summary }}</p>
          <div v-for="(h, i) in demoInsight.highlights" :key="i" class="insight-hl">· {{ h }}</div>
        </div>

        <div class="voice-row">
          <span class="mic">🎤</span>
          <span class="voice-text">“午餐 25 块”</span>
          <span class="voice-hint">语音识别到 → 三餐</span>
        </div>

        <div class="budget-box">
          <svg class="ring" viewBox="0 0 100 100" width="84" height="84">
            <circle cx="50" cy="50" r="40" fill="none" stroke="var(--border-light)" stroke-width="10" />
            <circle cx="50" cy="50" r="40" fill="none"
              :stroke="pct >= 100 ? 'var(--expense)' : 'var(--primary)'" stroke-width="10" stroke-linecap="round"
              :stroke-dasharray="`${dash} ${C}`" transform="rotate(-90 50 50)" />
          </svg>
          <div class="budget-text">
            <div class="budget-label">{{ demoBudget.monthLabel }}预算</div>
            <div class="budget-val amount">{{ formatMoney(demoBudget.spentMinor) }} / {{ formatMoney(demoBudget.totalMinor) }}</div>
            <div class="budget-sub" :class="{ over: pct >= 100 }">已用 {{ pct }}%{{ pct >= 100 ? ' · 超支预警' : '' }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.feature-grid { display: grid; grid-template-columns: 1fr 1.1fr; gap: clamp(24px, 5vw, 64px); align-items: center; }
.feature-copy .eyebrow { font-size: 13px; color: var(--primary); font-weight: 600; letter-spacing: .04em; }
.feature-copy h2 { font-size: clamp(26px, 4vw, 42px); line-height: 1.15; margin: 12px 0 16px; font-weight: 800; letter-spacing: -0.02em; }
.feature-copy .lead { color: var(--muted); line-height: 1.7; margin: 0 0 20px; font-size: 16px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.points li { position: relative; padding-left: 24px; color: var(--text); font-size: 15px; }
.points li::before { content: '✓'; position: absolute; left: 0; color: var(--primary); font-weight: 700; }
.demo-phone { display: flex; justify-content: center; }
.phone-card { width: min(100%, 340px); background: var(--card); border-radius: 24px; padding: 18px 16px; box-shadow: var(--shadow); border: 1px solid var(--border-light); }
.phone-title { display: flex; justify-content: space-between; align-items: center; font-weight: 700; margin-bottom: 12px; }
.pill.ai { padding: 2px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; background: var(--primary-soft); color: var(--primary); }
.insight-card { background: var(--primary-soft); border-radius: 14px; padding: 14px 16px; margin-bottom: 14px; }
.insight-summary { color: var(--text); font-size: 13px; line-height: 1.6; margin: 0 0 10px; }
.insight-hl { color: var(--muted); font-size: 12px; line-height: 1.7; }
.voice-row { display: flex; align-items: center; gap: 8px; padding: 10px; border: 1px dashed var(--border); border-radius: 12px; margin-bottom: 14px; }
.mic { font-size: 20px; }
.voice-text { font-size: 14px; font-weight: 600; }
.voice-hint { margin-left: auto; font-size: 12px; color: var(--income); }
.budget-box { display: flex; align-items: center; gap: 16px; }
.budget-text { display: flex; flex-direction: column; gap: 4px; }
.budget-label { font-size: 12px; color: var(--muted); }
.budget-val { font-weight: 700; font-size: 15px; }
.budget-sub { font-size: 12px; color: var(--income); }
.budget-sub.over { color: var(--expense); }
@media (max-width: 820px) { .feature-grid { grid-template-columns: 1fr; } }
</style>
