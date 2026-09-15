<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useDataStore } from '../../stores/data'
import { accounts } from '../../api/accounts'
import { formatMoney, parseMoneyToMinor, minorToDecimal, formatSigned } from '../../utils/money'
import { monthStart, nextMonthStart } from '../../utils/date'
import { countUp } from '../../utils/countUp'
import { useToast } from '../../composables/useToast'
import AccountIcon from '../../components/AccountIcon.vue'
import Modal from '../../components/ui/Modal.vue'
import ConfirmDialog from '../../components/ui/ConfirmDialog.vue'
import Btn from '../../components/ui/Btn.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import type { Account } from '../../types'

const data = useDataStore()
const toast = useToast()
const reveal = ref(true)
const loading = ref(false)

const total = computed(() => data.accts.reduce((s, a) => s + (a.balanceMinor || 0), 0))

// 本月结余：本月收支差（整数分），驱动总资产卡的结余光晕（≥0 绿晕呼吸 / <0 红晕静态警示）
const monthBalance = computed(() => {
  const start = monthStart(Date.now())
  const end = nextMonthStart(Date.now())
  let bal = 0
  for (const b of data.bills) {
    if (b.date < start || b.date >= end) continue
    bal += b.billType === 'EXPENSE' ? -b.amountMinor : b.amountMinor
  }
  return bal
})

// 账户色板：新建账户时按顺序取色（与 App 端资产色一致）
const palette = ['#28C145', '#06B4FD', '#F97D1D', '#8B5CF6', '#EF4444', '#64748B']

/* 总资产数字滚动：from 上次展示值滑动（countUp 内部带 rAF 取消与 reduced-motion 定格）。
 * reveal 切换会 v-if 重建元素，统一在 nextTick 后写值。 */
const totalEl = ref<HTMLElement | null>(null)
function renderTotal() {
  if (!reveal.value || !totalEl.value) return
  countUp(totalEl.value, total.value, 'assets:total', formatMoney, 500)
}
watch([total, reveal], () => nextTick(renderTotal))

async function sync() { await data.loadData(); toast.push('同步完成') }

/* ── 新建 / 编辑账户：Modal 表单（替换原 window.prompt），校验文案全中文 ── */
const formOpen = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editTarget = ref<Account | null>(null)
const formName = ref('')
const formBalance = ref('') // 「元」字符串，提交前经 parseMoneyToMinor 转「分」
const formErr = ref('')
const formSaving = ref(false)

function openCreate() {
  formMode.value = 'create'; editTarget.value = null
  formName.value = ''; formBalance.value = ''; formErr.value = ''
  formOpen.value = true
}
function openEdit(a: Account) {
  formMode.value = 'edit'; editTarget.value = a
  formName.value = a.name
  // 分 → 纯小数回显（不破坏整数分契约）
  formBalance.value = minorToDecimal(a.balanceMinor || 0)
  formErr.value = ''
  formOpen.value = true
}
async function submitForm() {
  const name = formName.value.trim()
  if (!name) { formErr.value = '请输入账户名称'; return }
  if (name.length > 20) { formErr.value = '账户名称不能超过 20 个字'; return }
  const balanceMinor = parseMoneyToMinor(formBalance.value.trim() || '0')
  if (balanceMinor === null) { formErr.value = '余额格式不正确，请输入非负金额（最多两位小数）'; return }
  formSaving.value = true
  try {
    if (formMode.value === 'create') {
      await accounts.create(name, palette[data.accts.length % 6], balanceMinor)
      toast.push('已新建账户')
    } else {
      await accounts.update(editTarget.value!.id, { name, balanceMinor })
      toast.push('已更新账户')
    }
    formOpen.value = false
    await data.refreshAccounts()
  } catch (e: any) {
    formErr.value = e?.message || '保存失败，请稍后重试'
  } finally { formSaving.value = false }
}

/* ── 删除账户：二次确认（danger 态 + loading，替换原 window.confirm）── */
const pendingRemove = ref<Account | null>(null)
const removing = ref(false)
async function confirmRemove() {
  const a = pendingRemove.value
  if (!a) return
  removing.value = true
  try {
    await accounts.remove(a.id)
    toast.push('已删除')
    pendingRemove.value = null
    await data.refreshAccounts()
  } catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
  finally { removing.value = false }
}

// 资产网格进场 stagger：每卡 +40ms，封顶 320ms
function gridDelay(i: number): number { return Math.min(i * 40, 320) }

onMounted(async () => {
  if (!data.accts.length) {
    loading.value = true
    try { await data.loadData() } finally { loading.value = false }
  }
  nextTick(renderTotal)
})
</script>

<template>
  <div class="page">
    <div class="head-row">
      <h2>资产管理</h2>
      <div class="head-actions">
        <Btn variant="ghost" @click="reveal = !reveal">{{ reveal ? '隐藏余额' : '显示余额' }}</Btn>
        <Btn variant="ghost" @click="sync">同步账单</Btn>
        <Btn variant="primary" @click="openCreate">新建账户</Btn>
      </div>
    </div>

    <!-- 总资产卡：countUp 滑动 + 按本月结余挂结余光晕 -->
    <div v-reveal class="net-card" :class="{ 'glow-positive': monthBalance >= 0, 'glow-negative': monthBalance < 0 }">
      <div class="net-label">总资产</div>
      <div class="net-val amount"><span v-if="reveal" ref="totalEl">¥0.00</span><template v-else>¥****</template></div>
      <div class="net-sub" :class="{ neg: monthBalance < 0 }">本月结余 {{ formatSigned(monthBalance) }}</div>
    </div>

    <div v-if="loading" class="acct-grid" aria-hidden="true">
      <div v-for="i in 3" :key="i" class="acct-card sk-card"><Skeleton height="40px" width="40px" round /><Skeleton height="18px" width="60%" /><Skeleton height="14px" width="40%" /></div>
    </div>

    <div v-else class="acct-grid">
      <div v-for="(a, i) in data.accts" :key="a.id" v-reveal="gridDelay(i)" class="acct-card" :style="{ borderLeftColor: a.iconColor }">
        <AccountIcon :icon-key="a.iconKey" :name="a.name" :color-hex="a.iconColor" :size="40" />
        <div class="acct-meta">
          <div class="acct-name">{{ a.name }}</div>
          <div class="acct-bal amount">{{ reveal ? formatMoney(a.balanceMinor || 0) : '¥***' }}</div>
        </div>
        <div class="acct-actions">
          <button class="link" @click="openEdit(a)">编辑</button>
          <button class="link danger" @click="pendingRemove = a">删除</button>
        </div>
      </div>
      <EmptyState
        v-if="!data.accts.length" class="empty-cell" icon="wallet"
        text="暂无账户" hint="点击右上角「新建账户」开始管理资产"
      />
    </div>

    <!-- 新建 / 编辑账户弹窗 -->
    <Modal v-model:open="formOpen" :title="formMode === 'create' ? '新建账户' : '编辑账户'" max-width="420px">
      <div class="form">
        <label class="field">
          <span class="field-label">账户名称</span>
          <input v-model="formName" class="sel" type="text" placeholder="如：微信 / 支付宝 / 现金" maxlength="20" />
        </label>
        <label class="field">
          <span class="field-label">余额（元）</span>
          <input v-model="formBalance" class="sel" type="text" inputmode="decimal" placeholder="0.00" />
        </label>
        <p v-if="formErr" class="form-err">{{ formErr }}</p>
        <div class="form-foot">
          <Btn variant="ghost" block :disabled="formSaving" @click="formOpen = false">取消</Btn>
          <Btn block :loading="formSaving" @click="submitForm">保存</Btn>
        </div>
      </div>
    </Modal>

    <!-- 删除账户二次确认 -->
    <ConfirmDialog
      :open="!!pendingRemove"
      title="删除账户"
      :message="`确定删除账户「${pendingRemove?.name ?? ''}」？删除后会同步到其他设备。`"
      confirm-text="删除"
      danger
      :loading="removing"
      @close="pendingRemove = null"
      @confirm="confirmRemove"
    />
  </div>
</template>

<style scoped>
.head-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; }

/* 总资产卡：结余光晕（glow-positive 8s 呼吸 / glow-negative 静态警示，见 theme.css） */
.net-card { background: var(--primary); color: var(--on-primary); border-radius: var(--radius); padding: 20px; margin: 16px 0; }
.net-label { font-weight: 600; opacity: .85; margin-bottom: 8px; }
.net-val { font-size: 32px; font-weight: 800; font-variant-numeric: tabular-nums; }
.net-sub { margin-top: 6px; font-size: 13px; opacity: .85; font-variant-numeric: tabular-nums; }
.net-sub.neg { opacity: 1; }

.acct-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
/* 账户卡 hover 微交互：轻浮起 + 阴影加深（时长/缓动走令牌，只动 transform/box-shadow） */
.acct-card {
  background: var(--card); border-left: 4px solid; border-radius: var(--radius); padding: 16px;
  box-shadow: var(--shadow-sm); display: flex; flex-direction: column; gap: 12px;
  transition: transform var(--dur-expand) var(--ease), box-shadow var(--dur-expand) var(--ease);
}
.acct-card:hover { transform: translateY(-2px); box-shadow: var(--shadow); }
/* 骨架占位卡：左边条退为中性色，避免 currentColor 深边突兀 */
.sk-card { border-left-color: var(--border); }
.acct-meta { display: flex; flex-direction: column; gap: 4px; }
.acct-name { font-weight: 600; }
.acct-bal { font-size: 18px; font-weight: 700; }
.acct-actions { display: flex; gap: 10px; margin-top: 4px; }
.link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; font-family: inherit; padding: 0; }
.link.danger { color: var(--expense); }
.empty-cell { grid-column: 1 / -1; }

/* 弹窗表单 */
.form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 13px; color: var(--muted); }
.form-err { margin: 0; color: var(--expense); font-size: 13px; }
.form-foot { display: flex; gap: 10px; margin-top: 4px; }

@media (max-width: 768px) { .acct-grid { grid-template-columns: 1fr; } }
</style>
