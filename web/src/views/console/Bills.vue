<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useDataStore } from '../../stores/data'
import { bills } from '../../api/bills'
import { fmtDateTime } from '../../utils/date'
import { formatMoney } from '../../utils/money'
import { countUp } from '../../utils/countUp'
import { toCsv } from '../../utils/csv'
import { useToast } from '../../composables/useToast'
import EditBillModal from './EditBillModal.vue'
import type { Bill } from '../../types'

const data = useDataStore()
const toast = useToast()

const search = ref('')
const searchFocus = ref(false)
const cat = ref('')
const from = ref('')
const to = ref('')
const editing = ref<Bill | null>(null)

const expEl = ref<HTMLSpanElement | null>(null)
const incEl = ref<HTMLSpanElement | null>(null)
const balEl = ref<HTMLSpanElement | null>(null)

const monthCategories = computed(() => {
  const s = new Set(data.bills.map((b) => b.categoryName))
  return Array.from(s)
})

const filtered = computed(() => data.bills.filter((b) => {
  if (search.value) {
    const q = search.value.toLowerCase()
    if (!((b.remark ?? '').toLowerCase().includes(q) || b.categoryName.toLowerCase().includes(q))) return false
  }
  if (cat.value && b.categoryName !== cat.value) return false
  if (from.value && !(b.date >= new Date(from.value + 'T00:00:00').getTime())) return false
  if (to.value && !(b.date <= new Date(to.value + 'T23:59:59').getTime())) return false
  return true
}))

async function load() { if (!data.bills.length) await data.loadData() }
onMounted(load)

function finalizeSummary() {
  // 金额一律按「分」整数求和；countUp 的中间帧可能是小数，交给 formatMoney 取整展示。
  const exp = data.bills.filter((b) => b.billType === 'EXPENSE').reduce((s, b) => s + b.amountMinor, 0)
  const inc = data.bills.filter((b) => b.billType === 'INCOME').reduce((s, b) => s + b.amountMinor, 0)
  const bal = inc - exp
  if (expEl.value) countUp(expEl.value, exp, 'bills:exp', (n) => formatMoney(n))
  if (incEl.value) countUp(incEl.value, inc, 'bills:inc', (n) => formatMoney(n))
  if (balEl.value) countUp(balEl.value, bal, 'bills:bal', (n) => formatMoney(n))
}
watch(() => data.bills, finalizeSummary, { deep: true })
onMounted(finalizeSummary)

async function removeBill(b: Bill) {
  if (!window.confirm('确定删除该账单？')) return
  try { await bills.remove(b.id); toast.push('已删除'); await data.loadData() }
  catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
}

function downloadCsv() {
  const rows: (string | number)[][] = [['日期', '类型', '分类', '子分类', '金额', '备注', '来源']]
  for (const b of filtered.value) {
    rows.push([
      fmtDateTime(b.date), b.billType === 'EXPENSE' ? '支出' : '收入', b.categoryName,
      b.subCategoryName || '', b.amountMinor / 100, b.remark || '', b.source, // 导出边界：分转元，便于用户在表格中阅读
    ])
  }
  const csv = '\uFEFF' + toCsv(rows)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = 'rinklnote.csv'; a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="page">
    <h2>账单</h2>
    <div class="summary-grid grid-3">
      <div class="summary-card"><div class="label">支出</div><div class="val amount expense" ref="expEl">¥0.00</div></div>
      <div class="summary-card"><div class="label">收入</div><div class="val amount income" ref="incEl">¥0.00</div></div>
      <div class="summary-card"><div class="label">结余</div><div class="val amount" ref="balEl">¥0.00</div></div>
    </div>

    <div class="filters">
      <input class="sel w160" type="text" placeholder="搜索..." v-model="search" />
      <select class="sel" v-model="cat">
        <option value="">全部分类</option>
        <option v-for="c in monthCategories" :key="c" :value="c">{{ c }}</option>
      </select>
      <input class="sel" type="date" v-model="from" />
      <span class="to-sep">至</span>
      <input class="sel" type="date" v-model="to" />
      <button class="btn ghost" @click="downloadCsv">导出CSV</button>
    </div>

    <div class="tbl-scroll">
      <table>
        <thead>
          <tr><th>日期</th><th>分类</th><th>子分类</th><th>金额</th><th>备注</th><th>来源</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="b in filtered" :key="b.id">
            <td>{{ fmtDateTime(b.date) }}</td>
            <td>{{ b.categoryName }}{{ b.subCategoryName ? ' / ' + b.subCategoryName : '' }}</td>
            <td>{{ b.subCategoryName || '-' }}</td>
            <td :class="['amount', b.billType === 'EXPENSE' ? 'expense' : 'income']">{{ b.billType === 'EXPENSE' ? '-' : '' }}{{ formatMoney(b.amountMinor) }}</td>
            <td>{{ b.remark || '-' }}</td>
            <td><span :class="['tag', 'tag-' + String(b.source).toLowerCase()]">{{ b.source }}</span></td>
            <td>
              <button class="link-btn" @click="editing = b">编辑</button>
              <button class="link-btn danger" @click="removeBill(b)">删除</button>
            </td>
          </tr>
          <tr v-if="!filtered.length">
            <td colspan="7" class="empty-cell">{{ search || cat || from || to ? '未找到匹配的账单，请调整筛选条件' : '暂无账单记录，记一笔开始吧' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <EditBillModal v-if="editing" :bill="editing" @close="editing = null" @saved="editing = null; data.loadData()" />
  </div>
</template>

<style scoped>
.summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 16px; }
.summary-card { background: var(--card); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.summary-card .label { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.summary-card .val { font-size: 24px; font-weight: 700; }
.val.expense { color: var(--expense); }
.val.income { color: var(--income); }
.filters { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
.sel { padding: 10px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 15px; }
.w160 { width: 160px; }
.to-sep { color: var(--muted); }
.btn.ghost { padding: 10px 16px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; cursor: pointer; }
.tbl-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
table { width: 100%; border-collapse: collapse; background: var(--card); border-radius: var(--radius); overflow: hidden; box-shadow: var(--shadow-sm); min-width: 680px; }
th, td { padding: 12px 14px; text-align: left; border-bottom: 1px solid var(--border-light); white-space: nowrap; font-size: 14px; }
th { color: var(--muted); font-weight: 600; }
.amount.expense { color: var(--expense); }
.amount.income { color: var(--income); }
.tag { padding: 2px 8px; border-radius: 6px; font-size: 12px; }
.tag-qq { background: var(--tag-qq-bg); color: var(--tag-qq-fg); }
.tag-web { background: var(--tag-web-bg); color: var(--tag-web-fg); }
.tag-app { background: var(--tag-app-bg); color: var(--tag-app-fg); }
.link-btn { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; margin-right: 10px; }
.link-btn.danger { color: var(--expense); }
.empty-cell { text-align: center; color: var(--muted); padding: 32px 0; }
</style>
