<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { templates } from '../../../api/templates'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
const data = useDataStore()
const toast = useToast()
const label = ref('')
const amount = ref('')
const catVal = ref('')
const acctId = ref('')
const list = ref<any[]>([])

function fmt(n: number) { return '¥' + n.toFixed(2) }
async function load() { try { list.value = await templates.list() } catch { } }
onMounted(load)
async function add() {
  if (!catVal.value || !acctId.value) return
  const [id, name] = catVal.value.split('||')
  try { await templates.create({ label: label.value || '未命名', amount: parseFloat(amount.value) || 0, categoryId: parseInt(id), categoryName: name || '', accountId: parseInt(acctId.value) }); toast.push('已添加'); label.value = ''; amount.value = ''; await load() }
  catch (e: any) { toast.push(e?.message || '添加失败', 'err') }
}
async function remove(id: number) {
  try { await templates.remove(id); await load() }
  catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
}
</script>

<template>
  <div class="card">
    <div class="card-title">记账模板</div>
    <div class="grid-2">
      <input class="sel" placeholder="标签(如:早餐)" v-model="label" />
      <input class="sel" type="number" step="0.01" placeholder="金额" v-model="amount" />
    </div>
    <div class="grid-2">
      <select class="sel" v-model="catVal"><option disabled value="">分类</option><option v-for="c in data.cats" :key="c.id" :value="c.id + '||' + c.name">{{ c.name }}</option></select>
      <select class="sel" v-model="acctId"><option disabled value="">账户</option><option v-for="a in data.accts" :key="a.id" :value="String(a.id)">{{ a.name }}</option></select>
    </div>
    <button class="btn primary" @click="add">添加</button>
    <div class="tmpl-list">
      <div v-for="t in list" :key="t.id" class="tmpl-row">
        <span>{{ t.label }} {{ fmt(t.amount) }} → {{ t.categoryName }}</span>
        <button class="link danger" @click="remove(t.id)">删</button>
      </div>
      <div v-if="!list.length" class="empty">暂无模板</div>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.sel { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; width: 100%; }
.btn.primary { padding: 10px 16px; border-radius: 10px; border: none; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; cursor: pointer; }
.tmpl-list { margin-top: 14px; }
.tmpl-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border-light); font-size: 14px; }
.link.danger { background: none; border: none; color: var(--expense); cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: var(--muted); font-size: 13px; padding: 16px 0; }
@media (max-width: 640px) { .grid-2 { grid-template-columns: 1fr; } }
</style>
