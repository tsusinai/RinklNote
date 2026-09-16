<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getAdminPushLogs, type AdminPushLogRow } from '../../api/admin'
import Card from '../../components/ui/Card.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'

/* 推送历史：分页倒序只读列表。服务端仅返回元数据（type/dayKey/pushed_at），无推送文案（隐私红线）。 */
const page = ref(1)
const totalPages = ref(1)
const total = ref(0)
const rows = ref<AdminPushLogRow[]>([])
const loading = ref(true)
const failed = ref(false)

async function load(p = page.value) {
  loading.value = true
  failed.value = false
  try {
    const r = await getAdminPushLogs(p)
    rows.value = r.items
    page.value = r.page
    totalPages.value = Math.max(1, r.totalPages)
    total.value = r.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
function go(p: number) { if (p >= 1 && p <= totalPages.value && p !== page.value) load(p) }
onMounted(() => load(1))

function fmtTime(sec: number): string {
  const d = new Date(sec)
  return isNaN(d.getTime()) ? String(sec) : d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div>
    <template v-if="loading">
      <Skeleton v-for="i in 6" :key="i" height="40px" style="margin-bottom: 8px" />
    </template>
    <EmptyState v-else-if="failed" icon="refresh" text="加载失败" hint="无法获取推送历史，请稍后重试">
      <button class="retry" @click="load()">重试</button>
    </EmptyState>
    <EmptyState v-else-if="!rows.length" icon="receipt" text="还没有推送记录" hint="机器人触发日报 / 提醒后会出现在这里" />
    <Card v-else :title="`共 ${total} 条推送`">
      <table class="tbl">
        <thead>
          <tr><th>类型</th><th>日期键</th><th class="num">用户 ID</th><th>推送时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="l in rows" :key="l.id">
            <td>{{ l.type }}</td>
            <td>{{ l.dayKey }}</td>
            <td class="num">{{ l.userId }}</td>
            <td>{{ fmtTime(l.pushedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="totalPages > 1" class="pager">
        <button :disabled="page <= 1" @click="go(page - 1)">‹ 上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="go(page + 1)">下一页 ›</button>
      </div>
      <p class="privacy-note">仅展示推送元数据，不含推送文案内容（隐私红线）。</p>
    </Card>
  </div>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th, .tbl td { text-align: left; padding: 9px 8px; }
.tbl th { color: var(--text-secondary, #6b7a87); font-weight: 500; font-size: 12px; }
.tbl tbody tr + tr { border-top: 1px solid var(--divider, #eef1f4); }
.num { text-align: right; }
.pager { display: flex; justify-content: center; align-items: center; gap: 14px; padding-top: 12px; font-size: 13px; }
.pager button {
  border: 1px solid var(--divider, #dfe5ea); background: none; color: inherit;
  padding: 6px 12px; border-radius: 8px; cursor: pointer; font-size: 12px;
}
.pager button:disabled { opacity: .4; cursor: default; }
.privacy-note { font-size: 12px; color: var(--text-tertiary, #9aa7b2); margin-top: 10px; }
.retry { margin-top: 12px; color: var(--primary, #2e7bd6); background: none; border: none; cursor: pointer; font-size: 13px; }
</style>
