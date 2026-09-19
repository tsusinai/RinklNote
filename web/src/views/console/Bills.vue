<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useDataStore } from '../../stores/data'
import { bills } from '../../api/bills'
import { fmtDateTime } from '../../utils/date'
import { formatMoney } from '../../utils/money'
import { categoryEmoji } from '../../utils/categoryIcon'
import { toCsv, billCsvRows } from '../../utils/csv'
import { useToast } from '../../composables/useToast'
import StatCard from '../../components/ui/StatCard.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import Icon from '../../components/ui/Icon.vue'
import ConfirmDialog from '../../components/ui/ConfirmDialog.vue'
import EditBillModal from './EditBillModal.vue'
import type { Bill } from '../../types'

const PAGE_SIZE = 50 // 服务端分页：每页 50 条
const SEARCH_DEBOUNCE_MS = 300 // 搜索词输入防抖：停顿后才发请求

const data = useDataStore()
const toast = useToast()

const search = ref('')
const cat = ref<number | ''>('') // 分类过滤直接绑定分类 id（服务端按 categoryId 精确过滤）
const from = ref('')
const to = ref('')
const page = ref(1)
const loading = ref(false)
const editing = ref<Bill | null>(null)
const pendingRemove = ref<Bill | null>(null)
const removing = ref(false)

// 搜索结果（服务端返回）：当前页账单 + 分页元数据 + 全集聚合（汇总卡跨页口径）
const list = ref<Bill[]>([])
const total = ref(0)
const totalPages = ref(1)
const sumExpense = ref(0)
const sumIncome = ref(0)

const hasActiveFilter = computed(() => !!(search.value || cat.value !== '' || from.value || to.value))

// 汇总卡口径 = 当前筛选全集（跨页）聚合，金额全程整数分，展示经 formatMoney 格式化
const summary = computed(() => ({
  count: total.value,
  exp: sumExpense.value,
  inc: sumIncome.value,
  bal: sumIncome.value - sumExpense.value,
}))

// 筛选 chips：把已生效条件可视化为可删除的 chip（点 × 清除对应条件）
const chips = computed(() => {
  const list: { key: string; label: string }[] = []
  if (search.value) list.push({ key: 'search', label: `搜索：${search.value}` })
  const catName = data.cats.find((c) => c.id === cat.value)?.name
  if (catName) list.push({ key: 'cat', label: `分类：${catName}` })
  if (from.value && to.value) list.push({ key: 'date', label: `日期：${from.value} ~ ${to.value}` })
  else if (from.value) list.push({ key: 'date', label: `日期：${from.value} 起` })
  else if (to.value) list.push({ key: 'date', label: `日期：至 ${to.value}` })
  return list
})

function clearChip(key: string) {
  if (key === 'search') search.value = ''
  else if (key === 'cat') cat.value = ''
  else { from.value = ''; to.value = '' }
}

function currentParams(paramsPage: number) {
  return {
    q: search.value.trim() || undefined,
    categoryId: cat.value === '' ? undefined : cat.value,
    from: from.value || undefined,
    to: to.value || undefined,
    page: paramsPage,
    pageSize: PAGE_SIZE,
  }
}

// 拉取服务端搜索结果；页码越界时收敛回最后一页（watcher 会以收敛后的页码再拉一次）
// 竞态守卫：连续变更筛选时多个请求并行在途，乱序返回会让过期响应覆盖新结果——
// 每次请求取递增序号，只有仍是最新序号的响应才允许落状态、报错与收 loading。
let fetchSeq = 0
async function fetchResults() {
  const mySeq = ++fetchSeq
  loading.value = true
  try {
    const res = await bills.search(currentParams(page.value))
    if (mySeq !== fetchSeq) return
    list.value = res.bills
    total.value = res.total
    totalPages.value = Math.max(1, res.totalPages)
    sumExpense.value = res.sumExpenseMinor
    sumIncome.value = res.sumIncomeMinor
    if (page.value > res.totalPages && page.value > 1) {
      page.value = Math.max(1, res.totalPages)
      return
    }
  } catch (e: any) {
    if (mySeq !== fetchSeq) return
    toast.push(e?.message || '搜索失败', 'err')
  } finally {
    if (mySeq === fetchSeq) loading.value = false
  }
}

// 筛选变化（除搜索词外）立即刷新并回第一页
watch([cat, from, to], () => { page.value = 1; fetchResults() })
// 搜索词防抖：停顿 300ms 后刷新并回第一页
let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(search, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { page.value = 1; fetchResults() }, SEARCH_DEBOUNCE_MS)
})
// 翻页
watch(page, () => { fetchResults() })

async function load() {
  loading.value = true
  try {
    // 分类下拉需要 cats；账单数据本身由搜索接口按页拉取
    if (!data.cats.length) await data.loadData()
    await fetchResults()
  } finally { loading.value = false }
}
onMounted(load)

// 行进场 stagger：每行 +30ms，封顶 300ms（配合全局 v-reveal 指令）
function rowDelay(i: number): number { return Math.min(i * 30, 300) }

// 分类点：按分类名查 store 的 iconName → emoji，未命中回退首字符
function catEmoji(categoryName: string): string {
  const c = data.cats.find((c) => c.name === categoryName)
  return categoryEmoji(c?.iconName, categoryName)
}

async function confirmRemove() {
  const b = pendingRemove.value
  if (!b) return
  removing.value = true
  try {
    await bills.remove(b.id)
    toast.push('已删除')
    pendingRemove.value = null
    await fetchResults()
  } catch (e: any) {
    toast.push(e?.message || '删除失败', 'err')
  } finally { removing.value = false }
}

// CSV 导出：金额全程整数分，分→元的展示统一走 money.ts 的 minorToDecimal（纯整数拆分，
// 不再使用破坏整数分契约的 minor / 100 浮点除法）；导出行与页面汇总同口径（同为当前筛选全集，
// 搜索服务端化后按筛选条件翻页拉取，不再依赖全量内存列表），行构建与命令面板共用 billCsvRows。
async function downloadCsv() {
  const all: Bill[] = []
  try {
    let exportPage = 1
    const exportPageSize = 200 // 导出用大页，减少请求数
    while (all.length < 100_000) { // 防御性上限，防止异常分页死循环
      const res = await bills.search({ ...currentParams(exportPage), pageSize: exportPageSize })
      all.push(...res.bills)
      if (!res.bills.length || exportPage >= res.totalPages) break
      exportPage++
    }
  } catch (e: any) {
    toast.push(e?.message || '导出失败', 'err')
    return
  }
  const csv = '\uFEFF' + toCsv(billCsvRows(all))
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = 'rinklnote.csv'; a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="page">
    <h2>账单</h2>

    <!-- 汇总卡：口径 = 当前筛选全集（跨页）聚合 -->
    <div class="summary-grid">
      <StatCard label="笔数" :value="String(summary.count)" />
      <StatCard label="总支出" :value="formatMoney(summary.exp)" tone="expense" />
      <StatCard label="总收入" :value="formatMoney(summary.inc)" tone="income" />
      <StatCard label="结余" :value="formatMoney(summary.bal)" :tone="summary.bal < 0 ? 'expense' : 'default'" :glow="summary.bal < 0 ? 'negative' : 'positive'" />
    </div>

    <div class="filters">
      <input class="sel w160" type="text" placeholder="搜索备注/分类..." v-model="search" />
      <select class="sel" v-model="cat">
        <option :value="''">全部分类</option>
        <option v-for="c in data.cats" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <input class="sel" type="date" v-model="from" />
      <span class="to-sep">至</span>
      <input class="sel" type="date" v-model="to" />
      <button class="btn ghost pressable" @click="downloadCsv">导出CSV</button>
    </div>

    <!-- 筛选 chips：220ms 缩放淡入淡出 -->
    <TransitionGroup v-if="chips.length" name="chip" tag="div" class="chips">
      <span v-for="c in chips" :key="c.key" class="chip">
        {{ c.label }}
        <button class="chip-x" type="button" :aria-label="`清除${c.label}`" @click="clearChip(c.key)">
          <Icon name="close" :size="11" />
        </button>
      </span>
    </TransitionGroup>

    <!-- 账单列表：桌面为网格行表格，<768px 自动卡片化 -->
    <div class="tbl-scroll">
      <div class="bill-list">
        <div class="bill-row head">
          <span>日期</span><span>分类</span><span>子分类</span><span class="c-amt">金额</span><span>备注</span><span>来源</span><span class="c-ops">操作</span>
        </div>

        <!-- 首屏数据未到时渲染骨架行 -->
        <div v-if="loading" class="sk-list" aria-hidden="true">
          <div v-for="i in 6" :key="i" class="sk-row">
            <Skeleton width="86px" /><Skeleton width="104px" /><Skeleton width="72px" />
            <Skeleton width="88px" /><Skeleton width="150px" /><Skeleton width="44px" />
          </div>
        </div>

        <TransitionGroup v-else name="row" tag="div" class="rows">
          <div v-for="(b, i) in list" :key="b.id" v-reveal="rowDelay(i)" class="bill-row">
            <span class="c-date">{{ fmtDateTime(b.date) }}</span>
            <span class="c-cat"><i class="cat-dot">{{ catEmoji(b.categoryName) }}</i>{{ b.categoryName }}</span>
            <span class="c-sub">{{ b.subCategoryName }}</span>
            <span class="c-amt amount" :class="b.billType === 'EXPENSE' ? 'expense' : 'income'">{{ b.billType === 'EXPENSE' ? '-' : '' }}{{ formatMoney(b.amountMinor) }}</span>
            <span class="c-remark" :title="b.remark || ''">{{ b.remark || '-' }}</span>
            <span class="c-src"><span :class="['tag', 'tag-' + String(b.source).toLowerCase()]">{{ b.source }}</span></span>
            <span class="c-ops">
              <button class="link-btn pressable" @click="editing = b">编辑</button>
              <button class="link-btn danger pressable" @click="pendingRemove = b">删除</button>
            </span>
          </div>
        </TransitionGroup>

        <EmptyState
          v-if="!loading && !list.length"
          icon="receipt"
          :text="hasActiveFilter ? '未找到匹配的账单' : '暂无账单记录'"
          :hint="hasActiveFilter ? '调整筛选条件后再试试' : '去记账页记一笔开始吧'"
        />
      </div>
    </div>

    <!-- 服务端分页页码器 -->
    <div v-if="!loading && totalPages > 1" class="pager">
      <button class="page-btn pressable" type="button" :disabled="page <= 1" @click="page--">上一页</button>
      <span class="page-info">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <button class="page-btn pressable" type="button" :disabled="page >= totalPages" @click="page++">下一页</button>
    </div>

    <EditBillModal v-if="editing" :bill="editing" @close="editing = null" @saved="editing = null; data.loadData(); fetchResults()" />

    <ConfirmDialog
      :open="!!pendingRemove"
      title="删除账单"
      message="确定删除该账单？删除后会同步到其他设备。"
      confirm-text="删除"
      danger
      :loading="removing"
      @close="pendingRemove = null"
      @confirm="confirmRemove"
    />
  </div>
</template>

<style scoped>
.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.filters { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }
.sel { padding: 10px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 15px; }
.w160 { width: 160px; }
.to-sep { color: var(--muted); }

/* 筛选 chips */
.chips { position: relative; display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 6px 4px 12px; border-radius: 999px; background: var(--primary-soft); color: var(--primary); font-size: 13px; }
.chip-x { display: inline-flex; align-items: center; justify-content: center; width: 18px; height: 18px; border-radius: 50%; border: none; background: transparent; color: inherit; cursor: pointer; padding: 0; }
.chip-x:hover { background: rgba(127,127,127,.18); }
.chip-move, .chip-enter-active, .chip-leave-active { transition: opacity var(--dur-expand) var(--ease), transform var(--dur-expand) var(--ease); }
.chip-enter-from, .chip-leave-to { opacity: 0; transform: scale(.85); }
.chip-leave-active { position: absolute; }

/* 列表卡片（桌面网格行） */
.tbl-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.bill-list { min-width: 860px; background: var(--card); border: 1px solid var(--border-light); border-radius: var(--radius); box-shadow: var(--shadow-sm); overflow: hidden; }
.bill-row { display: grid; grid-template-columns: 132px 1.2fr 0.9fr 120px 1.6fr 72px 104px; gap: 10px; align-items: center; padding: 11px 14px; border-bottom: 1px solid var(--border-light); font-size: 14px; }
.bill-row.head { color: var(--muted); font-weight: 600; font-size: 13px; background: var(--surface-2); }
.rows { position: relative; }
.rows .bill-row:last-child { border-bottom: none; }
.c-amt { text-align: right; font-variant-numeric: tabular-nums; }
.amount.expense { color: var(--expense); }
.amount.income { color: var(--income); }
.c-cat { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.cat-dot { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; background: var(--primary-soft); font-size: 13px; font-style: normal; flex: none; }
.c-remark { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.c-sub:empty::before { content: '-'; color: var(--muted); }
.tag { padding: 2px 8px; border-radius: 6px; font-size: 12px; }
.tag-qq { background: var(--tag-qq-bg); color: var(--tag-qq-fg); }
.tag-web { background: var(--tag-web-bg); color: var(--tag-web-fg); }
.tag-app { background: var(--tag-app-bg); color: var(--tag-app-fg); }
.link-btn { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; margin-right: 10px; }
.link-btn.danger { color: var(--expense); }

/* 筛选增删行：fade + FLIP move（只动 transform/opacity，220ms） */
.row-move { transition: transform var(--dur-expand) var(--ease); }
.row-enter-active, .row-leave-active { transition: opacity var(--dur-expand) var(--ease); }
.row-enter-from, .row-leave-to { opacity: 0; }
.row-leave-active { position: absolute; left: 0; right: 0; }

/* 骨架行 */
.sk-list { padding: 4px 0; }
.sk-row { display: flex; gap: 20px; padding: 15px 14px; border-bottom: 1px solid var(--border-light); }
.sk-row:last-child { border-bottom: none; }

/* 分页页码器 */
.pager { display: flex; align-items: center; justify-content: center; gap: 14px; margin-top: 14px; }
.page-btn { padding: 8px 16px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 13px; cursor: pointer; }
.page-btn:disabled { opacity: .45; cursor: not-allowed; }
.page-info { font-size: 13px; color: var(--muted); }

/* 移动端卡片化：<768px 每行账单渲染为卡片（金额右对齐、备注省略） */
@media (max-width: 767px) {
  .summary-grid { grid-template-columns: repeat(2, 1fr); }
  .tbl-scroll { overflow: visible; }
  .bill-list { min-width: 0; background: none; border: none; box-shadow: none; }
  .bill-row.head { display: none; }
  .rows { display: block; }
  .rows .bill-row:last-child { border-bottom: 1px solid var(--border-light); }
  .bill-row {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      "cat  amt"
      "sub  amt"
      "date date"
      "remark remark"
      "src  ops";
    gap: 5px 10px;
    background: var(--card);
    border: 1px solid var(--border-light);
    border-radius: 12px;
    padding: 12px 14px;
    margin-bottom: 10px;
  }
  .c-cat { grid-area: cat; font-weight: 600; }
  .c-sub { grid-area: sub; color: var(--muted); font-size: 12px; }
  .c-sub:empty { display: none; }
  .c-amt { grid-area: amt; align-self: center; font-size: 16px; font-weight: 700; }
  .c-date { grid-area: date; color: var(--muted); font-size: 12px; }
  .c-remark { grid-area: remark; color: var(--muted); font-size: 13px; }
  .c-src { grid-area: src; }
  .c-ops { grid-area: ops; justify-self: end; }
}
</style>
