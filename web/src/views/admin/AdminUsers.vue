<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getAdminUsers, type AdminUserRow } from '../../api/admin'
import Card from '../../components/ui/Card.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'

/* 用户管理（只读）：搜索（手机号尾段 / userId）+ 表格 + 分页。行点击弹只读详情。 */
const query = ref('')
const page = ref(1)
const totalPages = ref(1)
const total = ref(0)
const rows = ref<AdminUserRow[]>([])
const loading = ref(true)
const failed = ref(false)
const active = ref<AdminUserRow | null>(null)

async function load(p = page.value) {
  loading.value = true
  failed.value = false
  try {
    const r = await getAdminUsers(query.value.trim() || undefined, p)
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

function search() { load(1) }
function go(p: number) { if (p >= 1 && p <= totalPages.value && p !== page.value) load(p) }
onMounted(() => load(1))

function fmtTime(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div>
    <form class="search-bar" @submit.prevent="search">
      <input v-model="query" placeholder="手机号尾段或用户 ID" />
      <button type="submit" class="search-btn">搜索</button>
    </form>

    <template v-if="loading">
      <Skeleton v-for="i in 6" :key="i" height="40px" style="margin-bottom: 8px" />
    </template>
    <EmptyState v-else-if="failed" icon="refresh" text="加载失败" hint="无法获取用户列表，请稍后重试">
      <button class="retry" @click="load()">重试</button>
    </EmptyState>
    <EmptyState v-else-if="!rows.length" icon="search" text="没有匹配的用户" hint="换个关键词试试" />
    <Card v-else :title="`共 ${total} 位用户`">
      <table class="tbl">
        <thead>
          <tr><th>ID</th><th>手机号</th><th>注册时间</th><th class="num">账单数</th><th class="num">绑定通道</th></tr>
        </thead>
        <tbody>
          <tr v-for="u in rows" :key="u.id" class="row" @click="active = u">
            <td>{{ u.id }}</td>
            <td>{{ u.phone ?? '—' }}</td>
            <td>{{ fmtTime(u.createdAt) }}</td>
            <td class="num">{{ u.billCount }}</td>
            <td class="num">{{ u.boundChannels }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="totalPages > 1" class="pager">
        <button :disabled="page <= 1" @click="go(page - 1)">‹ 上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="go(page + 1)">下一页 ›</button>
      </div>
    </Card>

    <!-- 详情：只读轻量层（无需 Teleport 级 Modal，信息量小） -->
    <div v-if="active" class="detail-mask" @click.self="active = null">
      <div class="detail">
        <div class="detail-head">
          <strong>用户详情</strong>
          <button class="x" @click="active = null">✕</button>
        </div>
        <div class="detail-row"><span>ID</span><strong>{{ active.id }}</strong></div>
        <div class="detail-row"><span>手机号</span><strong>{{ active.phone ?? '—' }}</strong></div>
        <div class="detail-row"><span>注册时间</span><strong>{{ fmtTime(active.createdAt) }}</strong></div>
        <div class="detail-row"><span>账单数</span><strong>{{ active.billCount }}</strong></div>
        <div class="detail-row"><span>绑定通道数</span><strong>{{ active.boundChannels }}</strong></div>
        <p class="privacy-note">只读视图：不含账单明细（隐私红线）。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-bar { display: flex; gap: 8px; margin-bottom: 12px; }
.search-bar input {
  flex: 1; padding: 9px 12px; border: 1px solid var(--divider, #dfe5ea); border-radius: 8px;
  font-size: 13px; background: var(--surface, #fff); color: inherit;
}
.search-btn {
  padding: 9px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer;
  background: var(--primary, #2e7bd6); color: #fff;
}
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th, .tbl td { text-align: left; padding: 9px 8px; }
.tbl th { color: var(--text-secondary, #6b7a87); font-weight: 500; font-size: 12px; }
.tbl tbody tr + tr { border-top: 1px solid var(--divider, #eef1f4); }
.row { cursor: pointer; transition: background var(--dur-expand) var(--ease); }
.row:hover { background: var(--surface-2, #f2f5f8); }
.num { text-align: right; }
.pager { display: flex; justify-content: center; align-items: center; gap: 14px; padding-top: 12px; font-size: 13px; }
.pager button {
  border: 1px solid var(--divider, #dfe5ea); background: none; color: inherit;
  padding: 6px 12px; border-radius: 8px; cursor: pointer; font-size: 12px;
}
.pager button:disabled { opacity: .4; cursor: default; }
.retry { margin-top: 12px; color: var(--primary, #2e7bd6); background: none; border: none; cursor: pointer; font-size: 13px; }
.detail-mask {
  position: fixed; inset: 0; background: rgba(15, 30, 44, .45); z-index: 60;
  display: flex; align-items: center; justify-content: center; padding: 16px;
}
.detail {
  width: 100%; max-width: 380px; background: var(--surface, #fff); color: inherit;
  border-radius: 12px; padding: 16px;
}
.detail-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.x { background: none; border: none; color: var(--text-secondary, #6b7a87); cursor: pointer; font-size: 13px; }
.detail-row { display: flex; justify-content: space-between; padding: 9px 0; font-size: 13px; }
.detail-row + .detail-row { border-top: 1px solid var(--divider, #eef1f4); }
.privacy-note { font-size: 12px; color: var(--text-tertiary, #9aa7b2); margin-top: 10px; }
</style>
