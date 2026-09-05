<script setup lang="ts">
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
const data = useDataStore()
const toast = useToast()
async function sync() { await data.loadData(); toast.push('同步完成') }
function lastSyncStr() { return data.lastSync ? new Date(data.lastSync).toLocaleString() : '从未' }
</script>

<template>
  <div class="card">
    <div class="card-title">同步</div>
    <div class="row"><span class="k">上次同步</span><span class="v">{{ lastSyncStr() }}</span></div>
    <button class="btn ghost" @click="sync">立即同步</button>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.row { display: flex; justify-content: space-between; margin-bottom: 10px; }
.k { color: var(--muted); }
.btn.ghost { padding: 9px 14px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; cursor: pointer; }
</style>
