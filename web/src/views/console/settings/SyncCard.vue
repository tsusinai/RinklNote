<script setup lang="ts">
import { ref } from 'vue'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'

const data = useDataStore()
const toast = useToast()
const syncing = ref(false)
async function sync() {
  syncing.value = true
  try { await data.loadData(); toast.push('同步完成') }
  catch (e: any) { toast.push(e?.message || '同步失败', 'err') }
  finally { syncing.value = false }
}
function lastSyncStr() { return data.lastSync ? new Date(data.lastSync).toLocaleString() : '从未' }
</script>

<template>
  <Card title="同步">
    <div class="row"><span class="k">上次同步</span><span class="v">{{ lastSyncStr() }}</span></div>
    <Btn variant="ghost" :loading="syncing" @click="sync">立即同步</Btn>
  </Card>
</template>

<style scoped>
/* 卡片/标题/按钮样式全部走全局与基础件，仅保留本卡信息行的排版 */
.row { display: flex; justify-content: space-between; margin-bottom: 10px; font-size: 14px; }
.k { color: var(--muted); }
</style>
