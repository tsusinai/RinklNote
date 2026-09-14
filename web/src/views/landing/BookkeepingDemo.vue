<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { demoDays } from '../../mock/demo-data'
import { countUp } from '../../utils/countUp'
import { formatMoney, formatMoneyPlain } from '../../utils/format'

const totalEl = ref<HTMLSpanElement | null>(null)
onMounted(() => {
  // 演示动画：总支出 = 最近几天合计（totalMinor 为「分」）
  const sum = demoDays.reduce((s, d) => s + d.totalMinor, 0)
  // 动画中间帧为「分」浮点；此处「¥」由模板单独渲染，故只输出数字部分
  if (totalEl.value) countUp(totalEl.value, sum, 'landing:exp', (n) => formatMoneyPlain(n), 800)
})
</script>

<template>
  <div class="feature-grid">
    <div class="feature-copy" v-reveal>
      <span class="eyebrow">记账 · 账单流水</span>
      <h2>三秒记一笔，账单清清楚楚</h2>
      <p class="lead">支出收入用颜色分清，分类、账户、备注随手下单；账单按日归组，金额一目了然。</p>
      <ul class="points">
        <li>收支类型切换，语义色即刻区分</li>
        <li>分类 + 子分类，快捷记账自动带入</li>
        <li>账单日/月分组，localStorage 长存</li>
      </ul>
    </div>

    <div class="demo-phone" v-reveal>
      <div class="phone-card">
        <div class="phone-title">
          <span>快速记账</span>
          <span class="pill exp">支出</span>
        </div>
        <div class="amount-box">
          <span class="cny">¥</span>
          <span ref="totalEl" class="amount-big amount">0.00</span>
        </div>
        <div class="cat-row">
          <span class="cat on">三餐</span><span class="cat">交通</span><span class="cat">购物</span><span class="cat">娱乐</span>
        </div>
        <div class="bill-list">
          <div v-for="d in demoDays" :key="d.date" class="day-group">
            <div class="day-label"><span>{{ d.label }}</span><span class="day-total amount">{{ formatMoney(d.totalMinor) }}</span></div>
            <div v-for="b in d.bills" :key="b.id" class="bill-row">
              <span class="bill-cat">{{ b.category }}</span>
              <span class="bill-remark">{{ b.remark ?? '' }}</span>
              <span class="bill-amount amount" :class="b.kind === 'EXPENSE' ? 'exp' : 'inc'">{{ b.kind === 'EXPENSE' ? '-' : '+' }}{{ formatMoney(b.amountMinor) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.feature-grid { display: grid; grid-template-columns: 1fr 1.1fr; gap: clamp(24px, 5vw, 64px); align-items: center; }
.feature-copy .eyebrow { font-size: 13px; color: var(--income); font-weight: 600; letter-spacing: .04em; }
.feature-copy h2 { font-size: clamp(26px, 4vw, 42px); line-height: 1.15; margin: 12px 0 16px; font-weight: 800; letter-spacing: -0.02em; }
.feature-copy .lead { color: var(--muted); line-height: 1.7; margin: 0 0 20px; font-size: 16px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.points li { position: relative; padding-left: 24px; color: var(--text); font-size: 15px; }
.points li::before { content: '✓'; position: absolute; left: 0; color: var(--primary); font-weight: 700; }
.demo-phone { display: flex; justify-content: center; }
.phone-card { width: min(100%, 340px); background: var(--card); border-radius: 24px; padding: 18px 16px; box-shadow: var(--shadow); border: 1px solid var(--border-light); }
.phone-title { display: flex; justify-content: space-between; align-items: center; font-weight: 700; margin-bottom: 8px; }
.pill { padding: 2px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; }
.pill.exp { background: rgba(202,48,50,.1); color: var(--expense); }
.amount-box { display: flex; align-items: baseline; gap: 4px; margin-bottom: 12px; }
.amount-big { font-size: 34px; font-weight: 800; }
.cny { font-size: 20px; color: var(--muted); }
.cat-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.cat { padding: 6px 12px; border-radius: 12px; background: var(--card); border: 1px solid var(--border); font-size: 13px; color: var(--muted); }
.cat.on { background: var(--primary-soft); border-color: transparent; color: var(--primary); font-weight: 600; }
.bill-list { display: flex; flex-direction: column; gap: 10px; max-height: 300px; overflow-y: auto; }
.day-group { border-top: 1px solid var(--border-light); padding-top: 10px; }
.day-label { display: flex; justify-content: space-between; font-size: 12px; color: var(--muted); margin-bottom: 6px; }
.bill-row { display: grid; grid-template-columns: 90px 1fr auto; gap: 8px; align-items: center; padding: 6px 0; font-size: 13px; }
.bill-cat { font-weight: 600; }
.bill-remark { color: var(--muted); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bill-amount.exp { color: var(--expense); }
.bill-amount.inc { color: var(--income); }
@media (max-width: 820px) { .feature-grid { grid-template-columns: 1fr; } }
</style>
