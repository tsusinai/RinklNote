<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { useDataStore } from '../../stores/data'
import { insights } from '../../api/insights'
import { toMonthStr } from '../../utils/date'
import { dailyExpense, monthlyTrend, expenseByCategory, type ChartPeriod } from '../../utils/chartData'
import type { MonthlyReview } from '../../types'

use([CanvasRenderer, PieChart, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent])

const data = useDataStore()
const period = ref<ChartPeriod>('month')
const revMonth = ref(toMonthStr(Date.now()))
const review = ref<MonthlyReview | null>(null)
const reviewLoading = ref(false)

const palette = ['#7EC1FC', '#CA3032', '#04A433', '#F59E0B', '#8B5CF6', '#EC4899', '#06B6D4']

const pieData = computed(() => expenseByCategory(data.bills, period.value))
const lineData = computed(() => monthlyTrend(data.bills))
const barData = computed(() => dailyExpense(data.bills, period.value))

const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: (p: any) => `${p.name}: ¥${Number(p.value).toFixed(2)} (${p.percent}%)` },
  legend: { show: false },
  series: [{
    type: 'pie', radius: '62%', center: ['50%', '50%'], data: pieData.value,
    label: { formatter: (p: any) => `${p.name} ${p.percent}%` },
    color: pieData.value.map((_, i) => palette[i % palette.length]),
  }],
}))

const lineOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => '¥' + Number(v).toFixed(2) },
  legend: { top: 0 },
  grid: { left: 8, right: 8, top: 32, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: lineData.value.map((d) => d.name) },
  yAxis: { type: 'value' },
  series: [
    { name: '支出', type: 'line', smooth: true, data: lineData.value.map((d) => d.expense), itemStyle: { color: '#CA3032' }, lineStyle: { color: '#CA3032', width: 2 } },
    { name: '收入', type: 'line', smooth: true, data: lineData.value.map((d) => d.income), itemStyle: { color: '#04A433' }, lineStyle: { color: '#04A433', width: 2 } },
  ],
}))

const barOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => '¥' + Number(v).toFixed(2) },
  grid: { left: 8, right: 8, top: 24, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: barData.value.map((d) => d.name) },
  yAxis: { type: 'value' },
  series: [{ name: '支出', type: 'bar', data: barData.value.map((d) => d.value), itemStyle: { color: '#CA3032', borderRadius: [4, 4, 0, 0] } }],
}))

async function loadReview() {
  review.value = null
  reviewLoading.value = true
  try {
    review.value = await insights.monthlyReview(revMonth.value)
  } catch {
    review.value = null
  } finally { reviewLoading.value = false }
}

async function init() { if (!data.bills.length) await data.loadData(); await loadReview() }
onMounted(init)
watch(revMonth, loadReview)
</script>

<template>
  <div class="page">
    <h2>图表分析</h2>

    <div class="card rev-panel">
      <div class="rev-head">
        <h3>月度复盘</h3>
        <input class="sel" type="month" v-model="revMonth" />
      </div>
      <div v-if="reviewLoading">加载中…</div>
      <div v-else-if="!review" class="rev-body">月度复盘加载失败，请先登录并确保本月有数据</div>
      <div v-else-if="!review.summary" class="rev-body">本月暂无记账数据，记一笔即可看到复盘～</div>
      <div v-else class="rev-body">
        <p class="rev-summary">{{ review.summary }}</p>
        <div v-for="(h, i) in review.highlights" :key="i" class="rev-hl">· {{ h }}</div>
        <div v-if="review.spikeDays?.length" class="rev-sec">
          <div class="rev-heading">⚠️ 超标日</div>
          <div v-for="s in review.spikeDays.slice(0, 5)" :key="s.date" class="rev-line">- {{ s.date }} ¥{{ s.amount.toFixed(2) }}（超日均{{ s.ratioPct }}%）</div>
        </div>
        <div v-if="review.biggestSingle" class="rev-line">🔍 最大单笔：{{ review.biggestSingle.categoryName }} ¥{{ review.biggestSingle.amount.toFixed(2) }}（{{ review.biggestSingle.date }}）</div>
        <div v-if="review.topCategories?.length" class="rev-line">🧾 消费集中：{{ review.topCategories.map((c) => c.name + ' ¥' + c.amount.toFixed(2)).join('、') }}</div>
      </div>
    </div>

    <div class="period-toggle">
      <button v-for="p in (['week','month','year'] as ChartPeriod[])" :key="p" :class="['toggle-btn', { on: period === p }]" @click="period = p">
        {{ p === 'week' ? '本周' : p === 'month' ? '本月' : '本年' }}
      </button>
    </div>

    <div class="chart-card">
      <div class="card-title">支出分类占比 <span class="hint">{{ period === 'week' ? '近7天' : period === 'month' ? '本月' : '近365天' }}</span></div>
      <VChart v-if="pieData.length" :option="pieOption" class="chart" />
      <div v-else class="empty-chart">该时段暂无支出数据</div>
    </div>

    <div class="chart-card">
      <div class="card-title">月度趋势</div>
      <VChart :option="lineOption" class="chart" />
    </div>

    <div class="chart-card">
      <div class="card-title">每日支出 <span class="hint">{{ period === 'week' ? '近7天' : period === 'month' ? '本月' : '近365天' }}</span></div>
      <VChart v-if="barData.length" :option="barOption" class="chart" />
      <div v-else class="empty-chart">该时段暂无支出数据</div>
    </div>
  </div>
</template>

<style scoped>
.rev-panel { margin-bottom: 16px; }
.rev-head { display: flex; justify-content: space-between; align-items: center; }
.rev-head h3 { margin: 0; }
.sel { padding: 8px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; }
.rev-summary { white-space: pre-wrap; color: var(--text); line-height: 1.6; }
.rev-hl { color: var(--muted); line-height: 1.9; }
.rev-sec { margin-top: 10px; }
.rev-heading { font-weight: 600; margin-bottom: 6px; }
.rev-line { color: var(--muted); line-height: 1.8; font-size: 14px; }
.period-toggle { display: flex; gap: 8px; margin-bottom: 16px; }
.toggle-btn { padding: 8px 18px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on { background: var(--primary); color: #0b2b44; font-weight: 600; border-color: transparent; }
.chart-card { background: var(--card); border-radius: var(--radius); padding: 16px; margin-bottom: 16px; box-shadow: var(--shadow-sm); }
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.card-title .hint { font-size: 12px; color: var(--muted); font-weight: 400; margin-left: 8px; }
.chart { height: 300px; }
.empty-chart { height: 300px; display: grid; place-items: center; color: var(--muted); }
@media (max-width: 640px) { .chart { height: 240px; } }
</style>
