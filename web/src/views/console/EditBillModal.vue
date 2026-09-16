<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { bills } from '../../api/bills'
import { useDataStore } from '../../stores/data'
import { useToast } from '../../composables/useToast'
import { parseMoneyToMinor, minorToDecimal } from '../../utils/money'
import type { Bill } from '../../types'

const props = defineProps<{ bill: Bill }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()
const data = useDataStore()
const toast = useToast()

// 以展示值初始化（整数分 → 元串，走 money.ts 纯整数拆分，不用 minor/100 浮点除法）
const st = reactive({
  amount: minorToDecimal(props.bill.amountMinor),
  billType: props.bill.billType,
  catId: String(props.bill.categoryId),
  subCatName: props.bill.subCategoryName ?? '',
  acctId: String(props.bill.accountId),
  remark: props.bill.remark ?? '',
})
const err = ref('')
const busy = ref(false)
const baseUpdatedAt = ref(props.bill.updatedAt ?? undefined)
const cats = computed(() => data.cats.filter((c) => c.type === st.billType))
const catSel = computed(() => data.cats.find((c) => c.id === Number(st.catId)) ?? null)
const subs = computed(() => catSel.value?.subCategories ?? [])

async function save() {
  // 用户输入的是「元」，解析为「分」整数后提交
  const amountMinor = parseMoneyToMinor(st.amount)
  if (amountMinor === null || !st.catId || !st.acctId) { err.value = '请填写完整或金额非法'; return }
  const cat = catSel.value
  if (!cat) { err.value = '分类已不存在'; return }
  busy.value = true
  try {
    await bills.update(props.bill.id, {
      amountMinor, billType: st.billType, categoryId: cat.id, categoryName: cat.name,
      subCategoryName: st.subCatName || null, accountId: Number(st.acctId),
      remark: st.remark || null, baseUpdatedAt: baseUpdatedAt.value,
    })
    toast.push('账单已更新')
    emit('saved')
  } catch (e: any) {
    if (e?.statusCode === 409) {
      // 条件 PUT 冲突：载入服务端最新行，提示确认后重存
      const fresh: Bill | undefined = e.data
      if (fresh) {
        st.amount = minorToDecimal(fresh.amountMinor)
        st.billType = fresh.billType
        st.catId = String(fresh.categoryId)
        st.subCatName = fresh.subCategoryName ?? ''
        st.acctId = String(fresh.accountId)
        st.remark = fresh.remark ?? ''
        baseUpdatedAt.value = fresh.updatedAt ?? undefined
        err.value = '账单已在其他设备修改，已载入最新内容，请确认后重新保存'
        // 不 emit('saved')：弹窗保持打开，用户确认最新内容后点「保存」会以 fresh.updatedAt 作为 baseUpdatedAt 重发；父级列表在用户保存/关闭后刷新。
        return
      }
    }
    err.value = e?.message || '更新失败'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="overlay" @click.self="emit('close')">
    <div class="modal">
      <h3>编辑账单</h3>
      <div class="toggle">
        <button :class="['toggle-btn', { on: st.billType === 'EXPENSE', exp: st.billType === 'EXPENSE' }]" @click="st.billType = 'EXPENSE'">支出</button>
        <button :class="['toggle-btn', { on: st.billType === 'INCOME', inc: st.billType === 'INCOME' }]" @click="st.billType = 'INCOME'">收入</button>
      </div>
      <input class="amount-input amount" type="number" step="0.01" v-model="st.amount" />
      <select v-model="st.catId" class="sel">
        <option disabled value="">选择分类</option>
        <option v-for="c in cats" :key="c.id" :value="String(c.id)">{{ c.name }}</option>
      </select>
      <div v-if="subs.length" class="sub-grid">
        <button :class="['cat-btn', { on: st.subCatName === '' }]" @click="st.subCatName = ''">全部</button>
        <button v-for="s in subs" :key="s.name" :class="['cat-btn', { on: st.subCatName === s.name }]" @click="st.subCatName = s.name">{{ s.name }}</button>
      </div>
      <select v-model="st.acctId" class="sel">
        <option disabled value="">选择账户</option>
        <option v-for="a in data.accts" :key="a.id" :value="String(a.id)">{{ a.name }}</option>
      </select>
      <input class="sel" type="text" placeholder="备注 (可选)" v-model="st.remark" />
      <div v-if="err" class="msg err">{{ err }}</div>
      <div class="modal-actions">
        <button class="btn ghost" @click="emit('close')">取消</button>
        <button class="btn btn-primary" :disabled="busy" @click="save">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 200; display: flex; align-items: center; justify-content: center; padding: 16px; }
.modal { width: 100%; max-width: 420px; background: var(--card); border-radius: 18px; padding: 24px; display: flex; flex-direction: column; gap: 12px; box-shadow: var(--shadow); }
.modal h3 { margin: 0 0 4px; }
.toggle { display: flex; gap: 8px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 12px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on.exp { background: var(--expense); color: #fff; border-color: transparent; font-weight: 600; }
.toggle-btn.on.inc { background: var(--income); color: #fff; border-color: transparent; font-weight: 600; }
.amount-input { width: 100%; font-size: 32px; font-weight: 800; border: none; border-bottom: 2px solid var(--border); padding: 8px 0; background: none; color: var(--text); }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; }
.sub-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(64px, 1fr)); gap: 8px; }
.cat-btn { padding: 8px 4px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 12px; cursor: pointer; }
.cat-btn.on { background: var(--primary-soft); border-color: var(--primary); }
.msg.err { color: var(--expense); font-size: 14px; }
.modal-actions { display: flex; gap: 12px; margin-top: 8px; }
.btn { padding: 12px 18px; border-radius: 12px; border: none; font-size: 15px; cursor: pointer; }
.btn.ghost { background: transparent; color: var(--text); border: 1px solid var(--border); }
.btn-primary { background: var(--primary); color: var(--on-primary); font-weight: 600; }
</style>
