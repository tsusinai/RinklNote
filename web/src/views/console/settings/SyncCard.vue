<script setup lang="ts">
import { ref, computed } from 'vue'
import { useDataStore, SYNC_RESOURCE_LABELS } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'

/* Web 的「同步」= 从云端刷新数据（本地优先，本地已有数据始终可用），
 * 不是 App 的「待上传队列」——这里如实展示刷新结果，不伪装上传进度。
 * 状态 / 失败原因 / 受影响资源均来自 data store 的结构化同步结果。 */
const data = useDataStore()
const toast = useToast()
const syncing = ref(false)

const statusText = computed(() => {
  if (syncing.value) return '同步中…'
  switch (data.syncStatus) {
    case 'success': return '已同步'
    case 'partial': return '部分资源同步失败'
    case 'error': return '同步失败'
    case 'offline': return '网络不可用'
    case 'unauthorized': return '需要重新登录'
    case 'conflict': return '数据冲突'
    default: return '尚未同步'
  }
})

/* 状态配色：成功走收入绿，失败/冲突走支出红，其余中性 */
const statusClass = computed(() => {
  if (data.syncStatus === 'success') return 'ok'
  if (['partial', 'error', 'offline', 'conflict'].includes(data.syncStatus)) return 'bad'
  return ''
})

const lastSyncStr = computed(() => data.lastSync ? new Date(data.lastSync).toLocaleString() : '从未')

/* 失败详情（失败 / 冲突 / 需要登录时展示） */
const showFailBlock = computed(() => syncing.value
  ? false
  : ['partial', 'error', 'offline', 'conflict', 'unauthorized'].includes(data.syncStatus))
const failReason = computed(() => data.lastResult?.message ?? null)
const failTimeStr = computed(() => data.lastAttempt ? new Date(data.lastAttempt).toLocaleString() : '')
const failedLabels = computed(() =>
  (data.lastResult?.errors ?? [])
    .map((e) => SYNC_RESOURCE_LABELS[e.resource])
    .filter((v, i, arr) => arr.indexOf(v) === i)
    .join('、'))

const needLogin = computed(() => !syncing.value && data.syncStatus === 'unauthorized')
const btnText = computed(() => {
  if (data.syncStatus === 'idle') return '立即同步'
  if (showFailBlock.value) return '重试'
  return '立即同步'
})

async function sync() {
  syncing.value = true
  try {
    const r = await data.loadData()
    switch (r.status) {
      case 'success':
        toast.push('同步完成')
        break
      case 'unauthorized':
        toast.push('登录已过期，请重新登录', 'err')
        break
      case 'conflict':
        toast.push(r.message ?? '数据冲突，请刷新后再试', 'err', 4000)
        break
      case 'partial':
        toast.push(`部分内容未刷新：${r.message}（其余内容已更新）`, 'warn', 4000)
        break
      default:
        // error / offline：整体失败，本地数据仍可用
        toast.push(`同步失败：${r.message}，本地数据仍可用`, 'err', 4000)
    }
  } finally {
    syncing.value = false
  }
}

/* 需要重新登录：回登录页并携带当前地址（登录成功后原路返回，与 401 掉登录一致） */
function goLogin() {
  const here = window.location.pathname + window.location.search + window.location.hash
  window.location.href = here.startsWith('/login') ? '/login' : '/login?redirect=' + encodeURIComponent(here)
}
</script>

<template>
  <Card title="同步">
    <div class="row">
      <span class="k">当前状态</span>
      <span class="v" :class="statusClass">{{ statusText }}</span>
    </div>
    <div class="row">
      <span class="k">上次成功</span>
      <span class="v">{{ lastSyncStr }}</span>
    </div>

    <!-- 失败详情：原因 + 受影响资源 + 可恢复提示（保持到下次同步成功） -->
    <div v-if="showFailBlock" class="fail-block" role="alert">
      <p class="fail-line">失败原因：{{ failReason }}</p>
      <p v-if="failedLabels" class="fail-line">受影响资源：{{ failedLabels }}</p>
      <p v-if="failTimeStr" class="fail-line">失败时间：{{ failTimeStr }}</p>
      <p v-if="data.syncStatus !== 'unauthorized'" class="keep-note">同步失败不影响本地数据，页面内容仍可正常查看与记账，稍后重试即可。</p>
    </div>

    <p class="sync-note">「同步」指从云端刷新账单、分类与账户数据，不会上传或删除本地内容。</p>

    <Btn v-if="needLogin" variant="ghost" @click="goLogin">去登录</Btn>
    <Btn v-else variant="ghost" :loading="syncing" @click="sync">{{ btnText }}</Btn>
  </Card>
</template>

<style scoped>
/* 卡片/标题/按钮样式全部走全局与基础件，仅保留本卡信息行的排版 */
.row { display: flex; justify-content: space-between; margin-bottom: 10px; font-size: 14px; }
.k { color: var(--muted); }
.v.ok { color: var(--income); font-weight: 600; }
.v.bad { color: var(--expense); font-weight: 600; }
.fail-block {
  margin: 4px 0 10px; padding: 10px 12px; border-radius: 10px;
  background: color-mix(in srgb, var(--expense) 8%, var(--card));
  border: 1px solid color-mix(in srgb, var(--expense) 35%, transparent);
}
.fail-line { margin: 0 0 4px; font-size: 13px; color: var(--text); }
.keep-note { margin: 6px 0 0; font-size: 13px; color: var(--muted); }
.sync-note { margin: 0 0 10px; font-size: 12px; color: var(--muted); }
</style>
