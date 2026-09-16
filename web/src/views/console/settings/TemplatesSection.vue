<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { templates } from '../../../api/templates'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
import { formatMoney, parseMoneyToMinor } from '../../../utils/money'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'
import Skeleton from '../../../components/ui/Skeleton.vue'
import EmptyState from '../../../components/ui/EmptyState.vue'
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue'
import type { Template } from '../../../types'

const data = useDataStore()
const toast = useToast()
const label = ref('')
const amount = ref('')
const catVal = ref('')
const acctId = ref('')
const list = ref<Template[]>([])
const loading = ref(true)
const loadErr = ref(false)
const saving = ref(false)

/* 删除确认（danger 态 + loading） */
const pendingRemove = ref<Template | null>(null)
const removing = ref(false)

async function load() {
  loadErr.value = false
  try { list.value = await templates.list() } catch { loadErr.value = true }
  finally { loading.value = false }
}
onMounted(load)

async function add() {
  if (!catVal.value || !acctId.value) { toast.push('请选择分类与账户', 'err'); return }
  const [id, name] = catVal.value.split('||')
  // 用户输入的是「元」，解析为「分」再提交；金额必填且需合法
  const amountMinor = parseMoneyToMinor(amount.value)
  if (amountMinor === null) { toast.push('请输入有效的模板金额', 'err'); return }
  saving.value = true
  try {
    await templates.create({ label: label.value.trim() || '未命名', amountMinor, categoryId: parseInt(id), categoryName: name || '', accountId: parseInt(acctId.value) })
    toast.push('已添加'); label.value = ''; amount.value = ''; await load()
  } catch (e: any) { toast.push(e?.message || '添加失败', 'err') }
  finally { saving.value = false }
}
async function confirmRemove() {
  const t = pendingRemove.value
  if (!t) return
  removing.value = true
  try { await templates.remove(t.id); toast.push('已删除'); pendingRemove.value = null; await load() }
  catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
  finally { removing.value = false }
}
</script>

<template>
  <Card title="记账模板">
    <div class="grid-2">
      <input v-model="label" class="sel" placeholder="标签(如:早餐)" maxlength="10" />
      <input v-model="amount" class="sel" type="number" step="0.01" placeholder="金额" />
    </div>
    <div class="grid-2">
      <select v-model="catVal" class="sel"><option disabled value="">分类</option><option v-for="c in data.cats" :key="c.id" :value="c.id + '||' + c.name">{{ c.name }}</option></select>
      <select v-model="acctId" class="sel"><option disabled value="">账户</option><option v-for="a in data.accts" :key="a.id" :value="String(a.id)">{{ a.name }}</option></select>
    </div>
    <Btn :loading="saving" @click="add">添加</Btn>

    <!-- 加载态 -->
    <div v-if="loading" class="loading" aria-hidden="true">
      <Skeleton v-for="i in 3" :key="i" height="14px" width="75%" />
    </div>
    <!-- 失败态 -->
    <template v-else-if="loadErr">
      <EmptyState icon="refresh" text="模板加载失败" hint="请检查网络后重试" />
      <div class="retry-row"><Btn variant="ghost" @click="load">重试</Btn></div>
    </template>
    <!-- 空态 -->
    <EmptyState v-else-if="!list.length" icon="receipt" text="暂无模板" hint="添加常用模板后可一键记账" />
    <!-- 正常态 -->
    <div v-else class="tmpl-list">
      <div v-for="t in list" :key="t.id" class="tmpl-row">
        <span>{{ t.label }} {{ formatMoney(t.amountMinor) }} → {{ t.categoryName }}</span>
        <button class="link danger" @click="pendingRemove = t">删除</button>
      </div>
    </div>

    <ConfirmDialog
      :open="!!pendingRemove"
      title="删除模板"
      :message="`确定删除模板「${pendingRemove?.label ?? ''}」？`"
      confirm-text="删除"
      danger
      :loading="removing"
      @close="pendingRemove = null"
      @confirm="confirmRemove"
    />
  </Card>
</template>

<style scoped>
/* 卡片/标题/输入/按钮样式全部走全局与基础件，仅保留列表排版 */
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.tmpl-list { margin-top: 14px; }
.tmpl-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border-light); font-size: 14px; }
.tmpl-row:last-child { border-bottom: none; }
.link.danger { background: none; border: none; color: var(--expense); cursor: pointer; font-size: 13px; padding: 0; font-family: inherit; }
.loading { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
.retry-row { display: flex; justify-content: center; margin-top: 6px; }
@media (max-width: 640px) { .grid-2 { grid-template-columns: 1fr; } }
</style>
