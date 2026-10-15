<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { botInteraction, BotInteractionError, takeBotToken, type InteractionEdit, type InteractionView } from '../api/botInteraction'
import { formatMoney, minorToDecimal, parseMoneyToMinor } from '../utils/money'

const route = useRoute()
const router = useRouter()
let token = ''
let generation = 0
let initialized = false
const view = ref<InteractionView | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const blocked = ref(false)
const uncertain = ref(false)
const expired = ref(false)
const amount = ref('')
const categoryId = ref<number | null>(null)
const enabled = ref(false)
const time = ref('21:00')
const confirmUndo = ref(false)
let expiryTimer: ReturnType<typeof setTimeout> | undefined

const editable = computed(() => !!view.value && !view.value.consumed && !expired.value && !blocked.value && !uncertain.value && !loading.value && !submitting.value)
const expiryText = computed(() => view.value ? new Date(view.value.expiresAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }) : '')
const billDate = computed(() => view.value?.bill ? new Date(view.value.bill.date).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '')

function applyView(result: InteractionView) {
  view.value = result
  amount.value = result.bill ? minorToDecimal(result.bill.amountMinor) : ''
  categoryId.value = result.bill?.categoryId ?? null
  enabled.value = result.budget?.enabled ?? false
  time.value = result.budget ? `${String(result.budget.hour).padStart(2, '0')}:${String(result.budget.minute).padStart(2, '0')}` : '21:00'
  confirmUndo.value = false
  clearTimeout(expiryTimer)
  expired.value = result.expiresAt <= Date.now()
  if (!expired.value) expiryTimer = setTimeout(() => { expired.value = true }, Math.min(result.expiresAt - Date.now(), 2_147_483_647))
}

function showError(cause: unknown, mutation = false) {
  const status = cause instanceof BotInteractionError ? cause.status : 0
  blocked.value = [401, 403, 410, 409].includes(status)
  // 网络中断或服务异常时，先查询操作结果，避免连续重复提交。
  if (mutation) uncertain.value = status === 0 || status >= 500
  error.value = uncertain.value ? '操作结果暂未确认，请点击「重新查看」确认结果。' : cause instanceof Error ? cause.message : '操作未完成，请稍后重试。'
}

async function load() {
  if (loading.value || submitting.value) return
  if (!token) { blocked.value = true; error.value = '入口已失效，请回到小盘重新打开。'; return }
  loading.value = true
  error.value = ''
  const requestGeneration = generation
  try {
    const result = await botInteraction.load(token)
    if (requestGeneration !== generation) return
    applyView(result); uncertain.value = false; blocked.value = false
  }
  catch (cause) { if (requestGeneration === generation) showError(cause) }
  finally { if (requestGeneration === generation) loading.value = false }
}

async function submit(edit: InteractionEdit) {
  if (!editable.value) return
  if (view.value!.expiresAt <= Date.now()) { expired.value = true; return }
  submitting.value = true
  error.value = ''
  const requestGeneration = generation
  try {
    const result = await botInteraction.edit(token, edit)
    if (requestGeneration === generation) applyView(result)
  }
  catch (cause) { if (requestGeneration === generation) showError(cause, true) }
  finally { if (requestGeneration === generation) submitting.value = false }
}

function saveBill() {
  const amountMinor = parseMoneyToMinor(amount.value)
  if (amountMinor === null || amountMinor <= 0) { error.value = '请输入大于 0 的金额，最多保留两位小数。'; return }
  if (!view.value?.categories?.some(category => category.id === categoryId.value)) { error.value = '请选择账单分类。'; return }
  void submit({ operation: 'save', amountMinor, categoryId: categoryId.value! })
}

function saveReminders() {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time.value)) { error.value = '请选择有效的提醒时间。'; return }
  const [hour, minute] = time.value.split(':').map(Number)
  void submit({ operation: 'reminders', enabled: enabled.value, hour, minute })
}

watch(() => route.hash, async hash => {
  // 清理 hash 的 replace 不应清空内存票据；同页新票据必须重新读取。
  if (!hash && initialized) return
  initialized = true
  const newGeneration = ++generation
  token = takeBotToken(hash)
  clearTimeout(expiryTimer)
  view.value = null; error.value = ''; blocked.value = false; uncertain.value = false
  expired.value = false; loading.value = false; submitting.value = false; confirmUndo.value = false
  if (hash) await router.replace({ path: route.path, query: route.query, hash: '' })
  if (newGeneration === generation) void load()
}, { immediate: true })
onBeforeUnmount(() => { ++generation; token = ''; clearTimeout(expiryTimer) })
</script>

<template>
  <main class="bot-page">
    <header class="bot-header"><span class="bot-brand">记一笔 · 小盘</span><p>🧾 账单与预算</p></header>
    <section v-if="loading && !view" class="bot-state" role="status">正在读取，请稍候…</section>
    <section v-if="error" class="bot-error" role="alert">
      <p>{{ error }}</p>
      <p v-if="blocked" class="bot-muted">返回与小盘的聊天，重新打开最新入口。</p>
      <button v-else type="button" class="secondary" :disabled="loading || submitting" @click="load">{{ loading ? '读取中…' : '重新查看' }}</button>
    </section>
    <section v-if="view" class="bot-content">
      <h1>{{ view.title }}</h1>
      <p v-if="view.message" class="bot-result" role="status">{{ view.message }}</p>
      <p v-if="view.consumed" class="bot-muted">本次操作已完成。需要继续修改时，请回到小盘打开新的入口。</p>
      <p v-else-if="expired" class="bot-error" role="status">入口已过期，请回到小盘重新打开。</p>
      <p v-else class="bot-muted">入口有效至 {{ expiryText }}（北京时间）</p>

      <template v-if="view.kind === 'bill' && view.bill">
        <div class="bill-summary">
          <span>{{ view.bill.billType === 'INCOME' ? '收入' : '支出' }}</span>
          <strong class="amount">{{ formatMoney(view.bill.amountMinor) }}</strong>
          <p>{{ view.bill.categoryName }} · {{ billDate }} · {{ view.bill.accountName }}</p>
        </div>
        <p v-if="view.text" class="bot-detail">{{ view.text }}</p>
        <form @submit.prevent="saveBill">
          <fieldset :disabled="!editable">
            <label for="bill-amount">金额（元）</label>
            <input id="bill-amount" v-model="amount" inputmode="decimal" autocomplete="off" maxlength="18" required />
            <label for="bill-category">分类</label>
            <select id="bill-category" v-model="categoryId" required><option v-for="category in view.categories" :key="category.id" :value="category.id">{{ category.name }}</option></select>
            <button class="primary" type="submit">{{ submitting ? '处理中…' : '保存修改' }}</button>
          </fieldset>
        </form>
        <div v-if="confirmUndo && editable" class="undo-confirm">
          <p>撤销这笔 {{ formatMoney(view.bill.amountMinor) }} 的账单？</p>
          <div class="button-row"><button class="secondary" type="button" @click="confirmUndo = false">保留账单</button><button class="danger" type="button" @click="submit({ operation: 'undo' })">确认撤销</button></div>
        </div>
        <button v-else class="secondary undo" type="button" :disabled="!editable" @click="confirmUndo = true">撤销这一笔</button>
      </template>

      <template v-if="view.kind === 'budget' && view.budget">
        <dl class="budget-summary">
          <div><dt>本月预算</dt><dd>{{ view.budget.budgetMinor == null ? '尚未设置' : formatMoney(view.budget.budgetMinor) }}</dd></div>
          <div><dt>已用</dt><dd>{{ formatMoney(view.budget.spentMinor) }}</dd></div>
          <div><dt>{{ (view.budget.remainingMinor ?? 0) < 0 ? '已超出' : '剩余' }}</dt><dd>{{ view.budget.remainingMinor == null ? '—' : formatMoney(Math.abs(view.budget.remainingMinor)) }}</dd></div>
        </dl>
        <details class="budget-details"><summary>🔍 查看预算明细</summary><p class="bot-detail">{{ view.text || '暂无预算明细。' }}</p></details>
        <form @submit.prevent="saveReminders">
          <h2>🔔 日报提醒</h2>
          <fieldset :disabled="!editable">
            <label class="switch-row" for="reminder-enabled"><span>每天接收账单日报</span><input id="reminder-enabled" v-model="enabled" type="checkbox" /></label>
            <label for="reminder-time">提醒时间（北京时间）</label>
            <input id="reminder-time" v-model="time" type="time" required />
            <button class="primary" type="submit">{{ submitting ? '处理中…' : '保存提醒设置' }}</button>
          </fieldset>
        </form>
      </template>
    </section>
    <footer class="bot-muted">完成后可返回与小盘的聊天。</footer>
  </main>
</template>

<style scoped>
.bot-page { width: min(100%, 520px); margin: auto; padding: 24px 20px max(32px, env(safe-area-inset-bottom)); }
.bot-header { padding-bottom: 20px; border-bottom: 1px solid var(--border); margin-bottom: 24px; }
.bot-brand { font-weight: 750; color: var(--primary-ink); }
.bot-header p { margin: 8px 0 0; color: var(--muted); font-size: 14px; }
h1 { font-family: inherit; font-size: 24px; line-height: 1.4; margin: 0 0 16px; }
h2 { font-family: inherit; font-size: 18px; margin: 28px 0 16px; }
.bot-muted { color: var(--muted); font-size: 13px; line-height: 1.7; }
.bot-error, .bot-result, .bot-state { padding: 14px 16px; background: var(--surface-2); border-radius: var(--radius); margin: 16px 0; line-height: 1.7; overflow-wrap: anywhere; }
.bot-error { border-inline-start: 3px solid var(--expense); }
.bot-result { background: var(--primary-soft); color: var(--primary-ink); }
.bot-error p { margin: 0 0 8px; }
.bill-summary { padding: 24px 0; }
.bill-summary span { color: var(--muted); }
.bill-summary strong { display: block; font-size: clamp(32px, 8vw, 44px); line-height: 1.5; overflow-wrap: anywhere; }
.bill-summary p { margin: 4px 0 0; color: var(--muted); line-height: 1.6; }
.bot-detail { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
label { display: block; margin: 16px 0 8px; font-size: 14px; }
input, select { width: 100%; min-height: 48px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--card); color: var(--text); font: inherit; }
button { min-height: 46px; padding: 12px 18px; border: 0; border-radius: 10px; font-size: 15px; font-weight: 600; cursor: pointer; }
button:disabled, fieldset:disabled { opacity: .6; }
button:disabled { cursor: default; }
.primary { width: 100%; margin-top: 24px; background: var(--primary); color: var(--on-primary); }
.secondary { background: var(--surface-2); color: var(--text); }
.danger { background: var(--expense); color: var(--on-expense); }
.undo { width: 100%; margin-top: 12px; color: var(--expense); }
.undo-confirm { margin-top: 20px; padding-top: 8px; border-top: 1px solid var(--border); }
.button-row { display: flex; gap: 12px; }.button-row button { flex: 1; }
.budget-summary { padding: 8px 0; }
.budget-summary div { display: flex; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--border-light); }
dt { color: var(--muted); }dd { margin: 0; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.budget-details { margin-top: 20px; }summary { cursor: pointer; padding: 12px 0; color: var(--primary-ink); }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 8px 0; }
.switch-row input { width: 22px; height: 22px; min-height: 22px; accent-color: var(--primary-ink); }
footer { margin-top: 32px; text-align: center; }
</style>
