<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import VChart from '../../utils/echarts' // ECharts 按需注册统一出口（utils/echarts.ts）
import { useDataStore } from '../../stores/data'
import { useThemeStore } from '../../stores/theme'
import { insights } from '../../api/insights'
import { budgets } from '../../api/budgets'
import { toMonthStr, fmtDate, monthStart } from '../../utils/date'
import { assessBudgetRisk, monthExpenseSoFar, displayPct, type BudgetRiskAssessment } from '../../utils/budgetRisk'
import { dailyExpense, monthlyTrend, expenseByCategory, inPeriod, type ChartPeriod } from '../../utils/chartData'
import { CHART_ANIMATION, readChartPalette, readChartAxisColor, readExpenseIncomeColors, readChartLineColor } from '../../utils/echartsTheme'
import { formatMoney } from '../../utils/money'
import { exportMonthlyShareImage } from '../../utils/shareImage'
import { categoryEmoji } from '../../utils/categoryIcon'
import { useToast } from '../../composables/useToast'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import type { MonthlyReview, Budget } from '../../types'

const data = useDataStore()
const theme = useThemeStore()
const toast = useToast()
const period = ref<ChartPeriod>('month')
const revMonth = ref(toMonthStr(Date.now()))
const review = ref<MonthlyReview | null>(null)
const reviewLoading = ref(false)
const loading = ref(false)

// 下钻状态：空串 = 全部分类；点击扇区/图例联动下方明细列表，再次点击或「全部」复位
const drillCategory = ref('')

/* 主题联动：主题切换（显式 light/dark 或「跟随系统」下的系统亮暗变化）时
 * 递增 themeEpoch，让下面的 option 计算属性失效重算 —— 重新 getComputedStyle
 * 取图表令牌后经 notMerge setOption 全量重渲染，修复暗色下轴文字/配色看不清。 */
const themeEpoch = ref(0)
watch(() => theme.theme, () => { themeEpoch.value++ })
let sysThemeMq: MediaQueryList | null = null
function onSystemThemeChange() { themeEpoch.value++ }
onMounted(() => {
  sysThemeMq = window.matchMedia('(prefers-color-scheme: dark)')
  sysThemeMq.addEventListener?.('change', onSystemThemeChange)
})
onBeforeUnmount(() => sysThemeMq?.removeEventListener?.('change', onSystemThemeChange))

// 引用 themeEpoch 建立依赖：主题变化 → 重新读取 CSS 变量
function palette(): string[] { void themeEpoch.value; return readChartPalette() }
function axisColor(): string { void themeEpoch.value; return readChartAxisColor() }
function semanticColors() { void themeEpoch.value; return readExpenseIncomeColors() }
function lineColor(): string { void themeEpoch.value; return readChartLineColor() }

// 提示框基础样式：背景/描边/文字色跟随主题卡片令牌
function tooltipBase() {
  return {
    backgroundColor: 'var(--card)',
    borderColor: 'var(--border)',
    textStyle: { color: 'var(--text)' },
  }
}

const pieData = computed(() => expenseByCategory(data.bills, period.value))
const lineData = computed(() => monthlyTrend(data.bills))
const barData = computed(() => dailyExpense(data.bills, period.value))

const pieTotal = computed(() => pieData.value.reduce((s, d) => s + d.value, 0))

// 图例数据：带占比与色板对位，供自定义图例渲染（可点击下钻）
const legendItems = computed(() => pieData.value.map((d, i) => ({
  name: d.name,
  value: d.value,
  percent: pieTotal.value ? Math.round((d.value / pieTotal.value) * 1000) / 10 : 0,
  color: palette()[i % 7],
})))

const pieOption = computed(() => {
  const pal = palette()
  const active = drillCategory.value
  return {
    ...CHART_ANIMATION,
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => `${p.name}: ${formatMoney(Number(p.value))} (${p.percent}%)`,
      ...tooltipBase(),
    },
    legend: { show: false },
    series: [{
      type: 'pie', radius: '62%', center: ['50%', '50%'],
      data: pieData.value.map((d) => ({
        name: d.name, value: d.value,
        // 下钻时未选中扇区降透明度（update 300ms 过渡），未下钻全部原色
        itemStyle: active ? { opacity: d.name === active ? 1 : 0.32 } : undefined,
      })),
      label: {
        formatter: (p: any) => `${p.name} ${p.percent}%`,
        color: axisColor(),
      },
      color: pal,
    }],
  }
})

const lineOption = computed(() => {
  const axis = axisColor()
  const { expense, income } = semanticColors()
  const split = lineColor()
  return {
    ...CHART_ANIMATION,
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => formatMoney(Number(v)), ...tooltipBase() },
    legend: { top: 0, textStyle: { color: axis } },
    grid: { left: 8, right: 8, top: 32, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: lineData.value.map((d) => d.name), axisLabel: { color: axis }, axisLine: { lineStyle: { color: split } } },
    yAxis: { type: 'value', axisLabel: { color: axis }, splitLine: { lineStyle: { color: split } } },
    series: [
      { name: '支出', type: 'line', smooth: true, data: lineData.value.map((d) => d.expense), itemStyle: { color: expense }, lineStyle: { color: expense, width: 2 } },
      { name: '收入', type: 'line', smooth: true, data: lineData.value.map((d) => d.income), itemStyle: { color: income }, lineStyle: { color: income, width: 2 } },
    ],
  }
})

const barOption = computed(() => {
  const axis = axisColor()
  const split = lineColor()
  const { expense } = semanticColors()
  return {
    ...CHART_ANIMATION,
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => formatMoney(Number(v)), ...tooltipBase() },
    grid: { left: 8, right: 8, top: 24, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: barData.value.map((d) => d.name), axisLabel: { color: axis }, axisLine: { lineStyle: { color: split } } },
    yAxis: { type: 'value', axisLabel: { color: axis }, splitLine: { lineStyle: { color: split } } },
    series: [{ name: '支出', type: 'bar', data: barData.value.map((d) => d.value), itemStyle: { color: expense, borderRadius: [4, 4, 0, 0] } }],
  }
})

// 周期文案：与聚合口径一致（week=本周一至今 / month=本月 / year=今年）
const periodLabel = computed(() => period.value === 'week' ? '本周' : period.value === 'month' ? '本月' : '本年')

// 扇区/图例下钻：再次点击同一分类或点「全部」复位
function toggleDrill(name: string) { drillCategory.value = drillCategory.value === name ? '' : name }
function onPieClick(params: any) { if (params?.componentType === 'series' && params?.name) toggleDrill(params.name) }

// 明细列表：当前周期支出（含补零周期起点），再按选中分类过滤，最多展示 50 笔
const DETAIL_LIMIT = 50
const detailBills = computed(() => data.bills.filter((b) =>
  b.billType === 'EXPENSE' && inPeriod(b, period.value) && (!drillCategory.value || b.categoryName === drillCategory.value),
))
const detailShown = computed(() => detailBills.value.slice(0, DETAIL_LIMIT))
const detailTotal = computed(() => detailBills.value.reduce((s, b) => s + b.amountMinor, 0))

// 分类点：按分类名查 store 的 iconName → emoji，未命中回退首字符
function catEmoji(categoryName: string): string {
  const c = data.cats.find((c) => c.name === categoryName)
  return categoryEmoji(c?.iconName, categoryName)
}

async function loadReview() {
  review.value = null
  reviewLoading.value = true
  try {
    review.value = await insights.monthlyReview(revMonth.value)
  } catch {
    review.value = null
  } finally { reviewLoading.value = false }
}

/* 导出月度分享图（Task 3.5）：对当前选择的复盘月份出图，聚合走 utils/shareImage.ts（chartData 同口径） */
const shareBusy = ref(false)
async function exportShare() {
  const [y, m] = revMonth.value.split('-').map(Number)
  if (!y || !m) return
  shareBusy.value = true
  try {
    // 取当月 15 日为锚点（任意当月时刻均可，聚合只看自然月窗口）
    const ok = await exportMonthlyShareImage(data.bills, new Date(y, m - 1, 15).getTime())
    if (ok) toast.push('分享图已导出')
    else toast.push('导出失败：当前环境不支持画布', 'err')
  } finally { shareBusy.value = false }
}

/* 预算烧穿风险预警（Task 4.1 Web 侧）：本地实时派生、零存储。
 * 口径与 App 钉死对齐：pct = 预测周期末累计消耗 ÷ 月预算 × 100；<85 低 / 85~100 中 / >100 高。 */
const monthBudget = ref<Budget | null>(null)
async function loadMonthBudget() {
  try {
    const list = await budgets.list()
    monthBudget.value = list.find((b) => !b.deleted && b.monthStart === monthStart(Date.now())) ?? null
  } catch { monthBudget.value = null }
}
const risk = computed<BudgetRiskAssessment | null>(() => {
  const b = monthBudget.value
  if (!b || b.amountMinor <= 0) return null
  void data.bills.length // 依赖账单变化触发重算
  return assessBudgetRisk(b.amountMinor, monthExpenseSoFar(data.bills), Date.now())
})

async function init() {
  if (!data.bills.length) {
    loading.value = true
    try { await data.loadData() } finally { loading.value = false }
  }
  await loadReview()
  await loadMonthBudget()
}
onMounted(init)
watch(revMonth, loadReview)
</script>

<template>
  <div class="page">
    <h2>图表分析</h2>

    <div v-reveal class="card rev-panel">
      <div class="rev-head">
        <h3>月度复盘</h3>
        <div class="rev-actions">
          <input class="sel" type="month" v-model="revMonth" />
          <button class="share-btn" type="button" :disabled="shareBusy" aria-label="导出月度分享图" @click="exportShare">导出分享图</button>
        </div>
      </div>
      <div v-if="reviewLoading" class="rev-body">
        <Skeleton height="16px" /><Skeleton height="14px" width="70%" /><Skeleton height="14px" width="50%" />
      </div>
      <EmptyState v-else-if="!review" icon="chart" text="月度复盘加载失败" hint="请先登录并确保本月有数据" />
      <EmptyState v-else-if="!review.summary" icon="receipt" text="本月暂无记账数据" hint="记一笔即可看到复盘～" />
      <div v-else class="rev-body">
        <p class="rev-summary">{{ review.summary }}</p>
        <div v-for="(h, i) in review.highlights" :key="i" class="rev-hl">· {{ h }}</div>
        <div v-if="review.spikeDays?.length" class="rev-sec">
          <div class="rev-heading">⚠️ 超标日</div>
          <div v-for="s in review.spikeDays.slice(0, 5)" :key="s.date" class="rev-line">- {{ s.date }} {{ formatMoney(s.amountMinor) }}（超日均{{ s.ratioPct }}%）</div>
        </div>
        <div v-if="review.biggestSingle" class="rev-line">🔍 最大单笔：{{ review.biggestSingle.categoryName }} {{ formatMoney(review.biggestSingle.amountMinor) }}（{{ review.biggestSingle.date }}）</div>
        <div v-if="review.topCategories?.length" class="rev-line">🧾 消费集中：{{ review.topCategories.map((c) => c.name + ' ' + formatMoney(c.amountMinor)).join('、') }}</div>
      </div>
    </div>

    <div class="period-toggle" role="tablist" aria-label="统计周期">
      <button v-for="p in (['week','month','year'] as ChartPeriod[])" :key="p" :class="['toggle-btn', { on: period === p }]" @click="period = p">
        {{ p === 'week' ? '本周' : p === 'month' ? '本月' : '本年' }}
      </button>
    </div>

    <!-- 预算烧穿风险预警（Task 4.1）：仅本月有预算且预测 ≥85% 时展示 -->
    <div v-if="risk && risk.level !== 'low'" :class="['risk-banner', risk.level]" role="status">
      <span class="risk-badge">{{ risk.level === 'high' ? '高风险' : '中风险' }}</span>
      <span class="risk-text">
        按当前节奏，本月预算预计烧到 <b>{{ displayPct(risk.pct) }}%</b>（预测支出 {{ formatMoney(risk.predictedMinor) }} / 预算 {{ formatMoney(risk.budgetMinor) }}）——
        {{ risk.level === 'high' ? '小盘先替你捂住钱包，控制一下呀！' : '小盘帮你盯着呢，稳住！' }}
      </span>
    </div>

    <div v-reveal="60" class="chart-card">
      <div class="card-title">支出分类占比 <span class="hint">{{ periodLabel }}</span></div>
      <VChart
        v-if="pieData.length" :option="pieOption" class="chart" autoresize
        :update-options="{ notMerge: true }" @click="onPieClick"
      />
      <EmptyState v-else icon="chart" text="该时段暂无支出数据" hint="切换周期或去记一笔试试" />
      <!-- 自定义分类图例：可点击下钻，与扇区联动 -->
      <div v-if="pieData.length" class="legend">
        <button
          v-for="it in legendItems" :key="it.name" type="button"
          :class="['legend-chip', { on: drillCategory === it.name }]"
          :title="formatMoney(it.value)"
          @click="toggleDrill(it.name)"
        >
          <i class="dot" :style="{ background: it.color }" />
          <span class="lg-name">{{ it.name }}</span>
          <span class="lg-pct">{{ it.percent }}%</span>
        </button>
      </div>
      <div v-if="pieData.length" class="drill-bar">
        <span class="drill-label">
          明细口径：{{ drillCategory || '全部分类' }}
          <template v-if="detailBills.length"> · 共 {{ detailBills.length }} 笔 · {{ formatMoney(detailTotal) }}</template>
        </span>
        <button v-if="drillCategory" class="drill-reset" type="button" @click="drillCategory = ''">全部</button>
      </div>
    </div>

    <div v-reveal="120" class="chart-card">
      <div class="card-title">月度趋势 <span class="hint">近 12 个自然月</span></div>
      <VChart v-if="!loading" :option="lineOption" class="chart" autoresize :update-options="{ notMerge: true }" />
      <div v-else class="chart"><Skeleton height="100%" /></div>
    </div>

    <div v-reveal="180" class="chart-card">
      <div class="card-title">每日支出 <span class="hint">{{ periodLabel }}</span></div>
      <VChart v-if="!loading && barData.length" :option="barOption" class="chart" autoresize :update-options="{ notMerge: true }" />
      <div v-else-if="loading" class="chart"><Skeleton height="100%" /></div>
      <EmptyState v-else icon="chart" text="该时段暂无支出数据" hint="切换周期或去记一笔试试" />
    </div>

    <!-- 扇区下钻联动的明细列表 -->
    <div v-reveal="240" class="chart-card">
      <div class="card-title">分类明细 <span class="hint">{{ periodLabel }} · {{ drillCategory || '全部' }}</span></div>
      <div v-if="detailShown.length" class="detail-list">
        <div v-for="b in detailShown" :key="b.id" class="detail-row">
          <span class="d-date">{{ fmtDate(b.date) }}</span>
          <span class="d-cat"><i class="cat-dot">{{ catEmoji(b.categoryName) }}</i>{{ b.categoryName }}</span>
          <span class="d-remark" :title="b.remark || ''">{{ b.remark || '-' }}</span>
          <span class="d-amt amount">{{ formatMoney(b.amountMinor) }}</span>
        </div>
        <div v-if="detailBills.length > DETAIL_LIMIT" class="detail-more">仅显示最近 {{ DETAIL_LIMIT }} 笔，共 {{ detailBills.length }} 笔</div>
      </div>
      <EmptyState v-else icon="receipt" text="暂无匹配的支出明细" :hint="drillCategory ? '试试点「全部」查看其他分类' : '该时段还没有支出记录'" />
    </div>
  </div>
</template>

<style scoped>
.rev-panel { margin-bottom: 16px; }
.rev-head { display: flex; justify-content: space-between; align-items: center; }
.rev-head h3 { margin: 0; }
.rev-actions { display: flex; align-items: center; gap: 8px; }
/* 分享图导出按钮：轻量描边样式，与月份选择器同行 */
.share-btn {
  padding: 9px 14px; border-radius: 12px; border: 1px solid var(--border); background: none;
  color: var(--primary); font-size: 13px; cursor: pointer; font-family: inherit; white-space: nowrap;
  transition: background var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease);
}
.share-btn:hover { background: var(--primary-soft); border-color: var(--primary); }
.share-btn:disabled { opacity: .6; cursor: default; }
@media (max-width: 640px) { .rev-actions { flex-direction: column; align-items: stretch; } }
.rev-body { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.rev-summary { white-space: pre-wrap; color: var(--text); line-height: 1.6; margin: 0; }
.rev-hl { color: var(--muted); line-height: 1.9; }
.rev-sec { margin-top: 10px; }
.rev-heading { font-weight: 600; margin-bottom: 6px; }
.rev-line { color: var(--muted); line-height: 1.8; font-size: 14px; }

/* 周期切换：220ms 过渡（背景/文字/描边随选中态平滑变化） */
.period-toggle { display: flex; gap: 8px; margin-bottom: 16px; }

/* 预算烧穿风险预警条：中风险橙 / 高风险红，色彩全部走既有令牌（--chart-2 / --expense） */
.risk-banner {
  display: flex; align-items: center; gap: 10px; padding: 12px 16px;
  border-radius: var(--radius); margin-bottom: 16px; font-size: 14px; color: var(--text);
  background: var(--card); box-shadow: var(--shadow-sm);
}
.risk-banner.mid { border: 1px solid color-mix(in srgb, var(--chart-2) 45%, transparent); background: color-mix(in srgb, var(--chart-2) 10%, var(--card)); }
.risk-banner.high { border: 1px solid color-mix(in srgb, var(--expense) 45%, transparent); background: color-mix(in srgb, var(--expense) 8%, var(--card)); }
.risk-badge { flex: none; font-weight: 700; font-size: 12px; padding: 3px 10px; border-radius: 999px; color: #fff; }
.risk-banner.mid .risk-badge { background: var(--chart-2); }
.risk-banner.high .risk-badge { background: var(--expense); }
.risk-text { line-height: 1.6; }
.risk-text b { font-weight: 700; }
.toggle-btn {
  padding: 8px 18px; border-radius: 12px; border: 1px solid var(--border); background: none;
  color: var(--muted); font-size: 14px; cursor: pointer; font-family: inherit;
  transition: background var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease);
}
.toggle-btn.on { background: var(--primary); color: var(--on-primary); font-weight: 600; border-color: transparent; }

.chart-card { background: var(--card); border-radius: var(--radius); padding: 16px; margin-bottom: 16px; box-shadow: var(--shadow-sm); }
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.card-title .hint { font-size: 12px; color: var(--muted); font-weight: 400; margin-left: 8px; }
.chart { height: 300px; }

/* 自定义分类图例：chips 220ms 过渡 + 可点击下钻 */
.legend { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.legend-chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 5px 12px; border-radius: 999px;
  border: 1px solid var(--border-light); background: none; color: var(--text); font-size: 13px;
  cursor: pointer; font-family: inherit;
  transition: background var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease), transform var(--dur-expand) var(--ease);
}
.legend-chip:hover { transform: translateY(-1px); }
.legend-chip.on { background: var(--primary-soft); border-color: var(--primary); color: var(--primary); font-weight: 600; }
.legend-chip .dot { width: 10px; height: 10px; border-radius: 50%; flex: none; }
.lg-pct { color: var(--muted); font-size: 12px; }
.legend-chip.on .lg-pct { color: var(--primary); }

.drill-bar { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-top: 10px; }
.drill-label { color: var(--muted); font-size: 13px; }
.drill-reset {
  padding: 4px 14px; border-radius: 999px; border: 1px solid var(--border); background: none;
  color: var(--primary); font-size: 13px; cursor: pointer; font-family: inherit;
  transition: background var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease);
}
.drill-reset:hover { background: var(--primary-soft); border-color: var(--primary); }

/* 下钻明细列表 */
.detail-list { display: flex; flex-direction: column; }
.detail-row { display: grid; grid-template-columns: 72px 1.1fr 1.6fr 110px; gap: 10px; align-items: center; padding: 9px 2px; border-bottom: 1px solid var(--border-light); font-size: 14px; }
.detail-row:last-of-type { border-bottom: none; }
.d-date { color: var(--muted); font-size: 13px; }
.d-cat { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.cat-dot { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; background: var(--primary-soft); font-size: 13px; font-style: normal; flex: none; }
.d-remark { color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-amt { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }
.detail-more { text-align: center; color: var(--muted); font-size: 12px; padding: 10px 0 2px; }

@media (max-width: 640px) {
  .chart { height: 240px; }
  .detail-row { grid-template-columns: 64px 1fr 96px; }
  .d-remark { display: none; }
}
</style>
