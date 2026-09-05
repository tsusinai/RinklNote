<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { budgets } from '../../../api/budgets'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
import { monthStart, nextMonthStart } from '../../../utils/date'
import { countUp } from '../../../utils/countUp'
const data = useDataStore()
const toast = useToast()
const monthEditable = ref<number>(0) // amount editing via prompt (与原 web 一致用 prompt 较重); 用 input
const editAmount = ref('')
const cur = ref<{ amount: number } | null>(null)
const monthlyStart = monthStart(Date.now())
const ms = computed(() => monthlyStart)

async function load() {
  if (!data.bills.length) await data.loadData()
  try {
    const list = await budgets.list()
    cur.value = list.find((b) => b.monthStart === ms.value && !b.deleted) ?? null
  } catch { cur.value = null }
  finalize()
}
const expense = computed(() => Math.round(data.bills.filter((b) => b.billType === 'EXPENSE' && b.date >= ms.value && b.date < nextMonthStart(ms.value)).reduce((s, b) => s + b.amount, 0) * 100) / 100)
const pct = computed(() => cur.value && cur.value.amount > 0 ? Math.round((expense.value / cur.value.amount) * 100) : 0)

const expEl = ref<HTMLSpanElement | null>(null)
function finalize() { if (expEl.value) countUp(expEl.value, expense.value, 'budget:exp', (n) => '¥' + n.toFixed(2)) }
watch(data.bills, finalize, { deep: true })
onMounted(load)

async function saveBudget() {
  const amt = parseFloat(editAmount.value)
  if (isNaN(amt) || amt <= 0) { toast.push('请输入有效的预算金额', 'err'); return }
  await budgets.upsert(ms.value, amt)
  toast.push('预算已保存'); editAmount.value = ''; await load()
}
</script>

<template>
  <div class="card">
    <div class="card-title">月度预算</div>
    <div class="budget-head">
      <span class="bm">{{ new Date(ms).getFullYear() }}年{{ new Date(ms).getMonth() + 1 }}月</span>
      <span v-if="cur?.amount" class="btc amount">预算 ¥{{ cur.amount.toFixed(2) }}</span>
    </div>
    <div class="budget-exp">
      <span class="exp-label">本月支出</span>
      <span ref="expEl" class="amount exp" :class="{ over: cur?.amount && expense > cur?.amount }">¥0.00</span>
    </div>
    <template v-if="cur?.amount && cur.amount > 0">
      <div class="budget-bar-track"><div class="budget-bar" :class="{ over: pct >= 100 }" :style="{ width: pct + '%' }"></div></div>
      <div class="budget-note">已用 {{ pct }}%</div>
      <div class="budget-note">本月剩余 {{ Math.max(0, cur.amount - expense).toFixed(2) }}</div>
      <div v-if="expense > cur.amount" class="budget-note over-note">已超预算 ¥{{ (expense - cur.amount).toFixed(2) }}</div>
    </template>
    <div v-else class="budget-note">尚未设置本月预算，设置后可查看进度</div>
    <div class="budget-edit"><input class="sel" type="number" placeholder="设置预算金额" v-model="editAmount" /><button class="btn primary" @click="saveBudget">保存</button></div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.budget-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 10px; }
.bm { font-weight: 700; }
.btc { color: var(--muted); font-size: 13px; }
.budget-exp { display: flex; justify-content: space-between; margin-bottom: 10px; }
.exp-label { color: var(--muted); }
.exp { font-weight: 700; }
.exp.over { color: var(--expense); }
.budget-bar-track { height: 10px; background: var(--border-light); border-radius: 999px; overflow: hidden; }
.budget-bar { height: 100%; background: var(--primary); border-radius: 999px; }
.budget-bar.over { background: var(--expense); }
.budget-note { color: var(--muted); font-size: 13px; margin-top: 6px; }
.over-note { color: var(--expense); }
.budget-edit { display: flex; gap: 8px; margin-top: 14px; }
.sel { flex: 1; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; }
.btn.primary { padding: 10px 16px; border-radius: 10px; border: none; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; cursor: pointer; }
</style>
