<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useDataStore } from '../../stores/data'
import { bills } from '../../api/bills'
import { useToast } from '../../composables/useToast'
import { parseMoneyToMinor } from '../../utils/money'
import { categoryEmoji } from '../../utils/categoryIcon'
import { isEditableTarget, appendAmountKey } from '../../utils/keyboard'
import Card from '../../components/ui/Card.vue'
import Btn from '../../components/ui/Btn.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import ParticleBackground from '../../components/ParticleBackground.vue'
import type { MoneyStyle } from '../../types'

const data = useDataStore()
const toast = useToast()

const billType = ref<MoneyStyle>('EXPENSE')
const amount = ref('')
const catId = ref<number | null>(null)
const subCatName = ref<string>('')
const acctId = ref<number | null>(null)
const remark = ref('')
const msg = ref('')
const busy = ref(false)
// 首屏骨架：分类/账户数据未到位前渲染 Skeleton，避免空白
const loading = ref(true)

const cats = computed(() => data.cats.filter((c) => c.type === billType.value))
const selectedCat = computed(() => data.cats.find((c) => c.id === catId.value) ?? null)
const subs = computed(() => selectedCat.value?.subCategories ?? [])

onMounted(async () => {
  try { if (!data.cats.length) await data.loadData() } finally { loading.value = false }
})

function switchType(t: MoneyStyle) {
  billType.value = t
  catId.value = null
  subCatName.value = ''
}
function pickCat(id: number) { catId.value = id; subCatName.value = '' }
function pickSub(name: string) { subCatName.value = name }

async function submit() {
  // 用户输入的是「元」，解析为「分」整数后提交，避免 parseFloat(x)*100 浮点误差
  const amountMinor = parseMoneyToMinor(amount.value)
  if (amountMinor === null || !catId.value || !acctId.value) { msg.value = '请填写完整或金额非法'; return }
  const cat = selectedCat.value!
  busy.value = true
  try {
    await bills.create({
      amountMinor, billType: billType.value, categoryId: cat.id, categoryName: cat.name,
      subCategoryName: subCatName.value || null, accountId: acctId.value,
      remark: remark.value || null,
    })
    msg.value = '记账成功!'
    amount.value = ''; remark.value = ''
    toast.push('记账成功')
    await data.loadData()
  } catch (e: any) {
    msg.value = e?.message || '记账失败'
    toast.push(e?.message || '记账失败', 'err') // 提交失败同样走全局 toast 反馈
  } finally { busy.value = false }
}

// 内联提示配色：失败类文案标红，其余标绿
function msgClass(m: string): string {
  return /失败|错误|不能为空|已注册|不存在/.test(m) ? 'err' : 'ok'
}

/* ── 键盘流（Task 3.4）：数字键直输金额 / Enter 确认 / Esc 取消本次输入 ──
 * 确认流程完全复用现有 submit()（一步制校验+落库），本段只负责「按键 → 既有流程」的翻译。
 * 守卫：焦点在输入框/下拉时不拦截（原生输入优先）；修饰键组合（Ctrl+K 等）不碰。 */
const amountInputRef = ref<HTMLInputElement | null>(null)

/** 取消本次输入：Web 无记账抽屉，Esc 语义 = 清空金额/备注与错误提示（App 抽屉的对应操作） */
function clearEntry() {
  amount.value = ''
  remark.value = ''
  msg.value = ''
}

function onGlobalKey(e: KeyboardEvent) {
  if (loading.value || e.ctrlKey || e.metaKey || e.altKey) return
  if (isEditableTarget(e.target)) {
    // 焦点在金额输入框内：Enter 确认、Esc 清空；其余输入框/下拉走原生行为
    if (e.target === amountInputRef.value) {
      if (e.key === 'Enter') { e.preventDefault(); submit() }
      else if (e.key === 'Escape') { e.preventDefault(); clearEntry() }
    }
    return
  }
  // 数字 / 小数点 / 退格：直输金额并聚焦输入框（preventDefault 防止聚焦后按键二次落入输入框）
  const patched = appendAmountKey(amount.value, e.key)
  if (patched !== null) {
    e.preventDefault()
    amount.value = patched
    amountInputRef.value?.focus()
    return
  }
  if (e.key === 'Enter') { e.preventDefault(); submit() }
  else if (e.key === 'Escape') clearEntry()
}

onMounted(() => window.addEventListener('keydown', onGlobalKey))
onUnmounted(() => window.removeEventListener('keydown', onGlobalKey))
</script>

<template>
  <div class="page">
    <!-- 粒子星空背景：fixed 层（z-index:0），.page 抬到 z-index:1 浮在星点之上 -->
    <ParticleBackground />
    <h2>快速记账</h2>
    <Card>
      <!-- 首屏数据未到时渲染骨架占位 -->
      <div v-if="loading" class="sk" aria-hidden="true">
        <Skeleton height="40px" round />
        <Skeleton height="48px" width="55%" />
        <div class="sk-grid">
          <Skeleton v-for="i in 8" :key="i" height="64px" round />
        </div>
        <Skeleton height="44px" />
        <Skeleton height="44px" />
        <Skeleton height="48px" />
      </div>

      <template v-else>
        <div class="toggle">
          <button :class="['toggle-btn pressable', { on: billType === 'EXPENSE', exp: billType === 'EXPENSE' }]" @click="switchType('EXPENSE')">支出</button>
          <button :class="['toggle-btn pressable', { on: billType === 'INCOME', inc: billType === 'INCOME' }]" @click="switchType('INCOME')">收入</button>
        </div>

        <!-- 键盘流（Task 3.4）：页面任意处按数字键直输金额；Enter 确认、Esc 清空。
             type=text + inputmode=decimal：中间态「0.」等可正常回显（number 输入会被浏览器清空），合法性由 parseMoneyToMinor 把关 -->
        <input ref="amountInputRef" class="amount-input amount" type="text" inputmode="decimal" placeholder="0.00" v-model="amount" @input="msg=''" />

        <div class="section-label">分类</div>
        <!-- 响应式网格：auto-fill 自适应列数，窄屏不再被写死的 4 列压垮 -->
        <div v-if="cats.length" class="cat-grid">
          <button v-for="c in cats" :key="c.id" :class="['cat-btn', { on: catId === c.id }]" @click="pickCat(c.id)">
            <span class="cat-icon">{{ categoryEmoji(c.iconName, c.name) }}</span>
            <span class="cat-name">{{ c.name }}</span>
          </button>
        </div>
        <EmptyState v-else icon="book" text="暂无分类数据" hint="同步分类数据后再来记一笔" />

        <template v-if="subs.length">
          <div class="section-label">子分类</div>
          <div class="cat-grid sub">
            <button :class="['cat-btn', { on: subCatName === '' }]" @click="pickSub('')">全部</button>
            <button v-for="s in subs" :key="s.name" :class="['cat-btn', { on: subCatName === s.name }]" @click="pickSub(s.name)">{{ s.name }}</button>
          </div>
        </template>

        <div class="section-label">账户</div>
        <select v-model.number="acctId" class="sel">
          <option disabled value="">选择账户</option>
          <option v-for="a in data.accts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>

        <input class="sel" type="text" placeholder="备注 (可选)" v-model="remark" />

        <div v-if="msg" :class="['msg', msgClass(msg)]">{{ msg }}</div>

        <Btn class="block submit-btn" :loading="busy" @click="submit">记一笔</Btn>
      </template>
    </Card>
  </div>
</template>

<style scoped>
/* position:relative + z-index:1：保证页面内容浮在粒子星空（fixed z-index:0）之上 */
.page { max-width: 760px; position: relative; z-index: 1; }
.toggle { display: flex; gap: 8px; margin-bottom: 16px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 12px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on.exp { background: var(--expense); color: #fff; border-color: transparent; font-weight: 600; }
.toggle-btn.on.inc { background: var(--income); color: #fff; border-color: transparent; font-weight: 600; }
.amount-input { width: 100%; font-size: 38px; font-weight: 800; border: none; border-bottom: 2px solid var(--border); padding: 12px 0; background: none; color: var(--text); margin-bottom: 16px; }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
/* 响应式网格：auto-fill 自适应列数（窄屏 3~4 列、宽屏 8~9 列），替代写死列数的 .grid-4 */
.cat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(76px, 1fr)); gap: 10px; }
.cat-btn { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 6px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); cursor: pointer; font-size: 13px; font-family: inherit; /* 选中态颜色 220ms 过渡 */ transition: background-color var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease); }
.cat-btn.on { background: var(--primary-soft); border-color: var(--primary); /* 选中 pop：scale 1.03 回落 */ animation: cat-pop var(--dur-expand) var(--ease); }
@keyframes cat-pop { 0% { transform: scale(1); } 50% { transform: scale(1.03); } 100% { transform: scale(1); } }
.cat-icon { font-size: 22px; }
.cat-name { font-size: 12px; }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; }
.sel + .sel { margin-top: 10px; }
.msg { margin: 12px 0; font-size: 14px; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }
.submit-btn { margin-top: 12px; }
/* 骨架占位 */
.sk { display: flex; flex-direction: column; gap: 14px; }
.sk-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(76px, 1fr)); gap: 10px; }
</style>
