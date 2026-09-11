<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useDataStore } from '../../stores/data'
import { bills } from '../../api/bills'
import { useToast } from '../../composables/useToast'
import { parseMoneyToMinor } from '../../utils/money'
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

const cats = computed(() => data.cats.filter((c) => c.type === billType.value))
const selectedCat = computed(() => data.cats.find((c) => c.id === catId.value) ?? null)
const subs = computed(() => selectedCat.value?.subCategories ?? [])

onMounted(() => { if (!data.cats.length) data.loadData() })

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
  } finally { busy.value = false }
}

// 图标映射：iconName 字符串 → 简单首字符或 emoji；原 web 用 emoji 分类图标。保持轻量。
function icon(name: string): string {
  const map: Record<string, string> = { food: '🍚', transport: '🚌', shop: '🛒', fun: '🎬', travel: '✈️', bill: '🧾', other: '📦' }
  return map[name] ?? name[0] ?? '●'
}
function msgClass(m: string): string {
  return /失败|错误|不能为空|已注册|不存在/.test(m) ? 'err' : 'ok'
}
</script>

<template>
  <div class="page">
    <h2>快速记账</h2>
    <div class="card">
      <div class="toggle">
        <button :class="['toggle-btn', { on: billType === 'EXPENSE', exp: billType === 'EXPENSE' }]" @click="switchType('EXPENSE')">支出</button>
        <button :class="['toggle-btn', { on: billType === 'INCOME', inc: billType === 'INCOME' }]" @click="switchType('INCOME')">收入</button>
      </div>

      <input class="amount-input amount" type="number" step="0.01" placeholder="0.00" v-model="amount" @input="msg=''" />

      <div class="section-label">分类</div>
      <div class="grid-4">
        <button v-for="c in cats" :key="c.id" :class="['cat-btn', { on: catId === c.id }]" @click="pickCat(c.id)">
          <span class="cat-icon">{{ c.iconName ? icon(c.iconName) : c.name[0] }}</span>
          <span class="cat-name">{{ c.name }}</span>
        </button>
        <div v-if="!cats.length" class="empty-inline">{{ cats.length ? '' : '暂无分类数据' }}</div>
      </div>

      <div v-if="subs.length" class="grid-4 sub">
        <button :class="['cat-btn', { on: subCatName === '' }]" @click="pickSub('')">全部</button>
        <button v-for="s in subs" :key="s.name" :class="['cat-btn', { on: subCatName === s.name }]" @click="pickSub(s.name)">{{ s.name }}</button>
      </div>

      <div class="section-label">账户</div>
      <select v-model.number="acctId" class="sel">
        <option disabled value="">选择账户</option>
        <option v-for="a in data.accts" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select>

      <input class="sel" type="text" placeholder="备注 (可选)" v-model="remark" />

      <div v-if="msg" :class="['msg', msgClass(msg)]">{{ msg }}</div>

      <button class="btn btn-primary block" :disabled="busy" @click="submit">记一笔</button>
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 760px; }
.toggle { display: flex; gap: 8px; margin-bottom: 16px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on.exp { background: var(--expense); color: #fff; border-color: transparent; font-weight: 600; }
.toggle-btn.on.inc { background: var(--income); color: #fff; border-color: transparent; font-weight: 600; }
.amount-input { width: 100%; font-size: 38px; font-weight: 800; border: none; border-bottom: 2px solid var(--border); padding: 12px 0; background: none; color: var(--text); margin-bottom: 16px; }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
.grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.grid-4.sub { grid-template-columns: repeat(4, 1fr); }
.cat-btn { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 6px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); cursor: pointer; }
.cat-btn.on { background: var(--primary-soft); border-color: var(--primary); }
.cat-icon { font-size: 22px; }
.cat-name { font-size: 12px; }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; }
.empty-inline { grid-column: 1 / -1; text-align: center; color: var(--muted); font-size: 13px; padding: 16px 0; }
.msg { margin: 12px 0; font-size: 14px; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }
.btn { padding: 14px 18px; border-radius: 12px; border: none; font-size: 15px; cursor: pointer; }
.btn-primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.block { width: 100%; margin-top: 12px; }
</style>
