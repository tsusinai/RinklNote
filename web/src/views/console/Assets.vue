<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useDataStore } from '../../stores/data'
import { accounts } from '../../api/accounts'
import { formatMoney, parseMoneyToMinor } from '../../utils/money'
import { useToast } from '../../composables/useToast'
import AccountIcon from '../../components/AccountIcon.vue'

const data = useDataStore()
const toast = useToast()
const reveal = ref(true)

const total = computed(() => data.accts.reduce((s, a) => s + (a.balanceMinor || 0), 0))
const palette = ['#28C145', '#06B4FD', '#F97D1D', '#8B5CF6', '#EF4444', '#64748B']

async function sync() { await data.loadData(); toast.push('同步完成') }

async function createAccount() {
  const name = window.prompt('请输入账户名称')
  if (!name) return
  const bal = window.prompt('请输入账户余额', '0')
  // 用户输入的是「元」，解析为「分」整数后提交
  const balanceMinor = parseMoneyToMinor(bal ?? '0')
  if (balanceMinor === null || balanceMinor < 0) {
    toast.push('余额格式不正确', 'err'); return
  }
  try { await accounts.create(name, palette[data.accts.length % 6], balanceMinor); await data.refreshAccounts(); toast.push('已新建账户') }
  catch (e: any) { toast.push(e?.message || '新建失败', 'err') }
}
async function editBalance(id: number, cur: number) {
  // cur 为分，prompt 展示为「元」便于阅读
  const v = window.prompt('输入新余额', (cur / 100).toFixed(2))
  if (v == null) return
  const n = parseMoneyToMinor(v)
  if (n === null) { toast.push('余额格式不正确', 'err'); return }
  try { await accounts.update(id, { balanceMinor: n }); await data.refreshAccounts(); toast.push('已更新余额') }
  catch (e: any) { toast.push(e?.message || '更新失败', 'err') }
}
async function rename(id: number, cur: string) {
  const v = window.prompt('输入新名称', cur)
  if (v && v !== cur) { try { await accounts.update(id, { name: v }); await data.refreshAccounts(); toast.push('已重命名') } catch (e: any) { toast.push(e?.message || '重命名失败', 'err') } }
}
async function remove(id: number, name: string) {
  if (!window.confirm(`确定删除账户 ${name}？`)) return
  try { await accounts.remove(id); await data.refreshAccounts(); toast.push('已删除') }
  catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
}
onMounted(() => { if (!data.accts.length) data.loadData() })
</script>

<template>
  <div class="page">
    <div class="head-row">
      <h2>资产管理</h2>
      <div class="head-actions">
        <button class="btn ghost" @click="reveal = !reveal">{{ reveal ? '隐藏余额' : '显示余额' }}</button>
        <button class="btn ghost" @click="sync">同步账单</button>
        <button class="btn primary" @click="createAccount">新建账户</button>
      </div>
    </div>

    <div class="net-card">
      <div class="net-label">总资产</div>
      <div class="net-val amount">{{ reveal ? formatMoney(total) : '¥****' }}</div>
    </div>

    <div class="acct-grid">
      <div v-for="a in data.accts" :key="a.id" class="acct-card" :style="{ borderLeftColor: a.iconColor }">
        <AccountIcon :icon-key="a.iconKey" :name="a.name" :color-hex="a.iconColor" :size="40" />
        <div class="acct-meta">
          <div class="acct-name">{{ a.name }}</div>
          <div class="acct-bal amount">{{ reveal ? formatMoney(a.balanceMinor || 0) : '¥***' }}</div>
        </div>
        <div class="acct-actions">
          <button class="link" @click="editBalance(a.id, a.balanceMinor || 0)">编辑余额</button>
          <button class="link" @click="rename(a.id, a.name)">重命名</button>
          <button class="link danger" @click="remove(a.id, a.name)">删除</button>
        </div>
      </div>
      <div v-if="!data.accts.length" class="empty-cell">暂无账户数据，请检查网络连接后刷新页面</div>
    </div>
  </div>
</template>

<style scoped>
.head-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.btn { padding: 9px 14px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.net-card { background: var(--primary); color: #0b2b44; border-radius: var(--radius); padding: 20px; margin: 16px 0; }
.net-label { font-weight: 600; opacity: .85; margin-bottom: 8px; }
.net-val { font-size: 32px; font-weight: 800; }
.acct-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.acct-card { background: var(--card); border-left: 4px solid; border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); display: flex; flex-direction: column; gap: 12px; }
.acct-meta { display: flex; flex-direction: column; gap: 4px; }
.acct-name { font-weight: 600; }
.acct-bal { font-size: 18px; font-weight: 700; }
.acct-actions { display: flex; gap: 10px; margin-top: 4px; }
.link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; }
.link.danger { color: var(--expense); }
.empty-cell { grid-column: 1 / -1; text-align: center; color: var(--muted); padding: 32px 0; }
@media (max-width: 768px) { .acct-grid { grid-template-columns: 1fr; } }
</style>
