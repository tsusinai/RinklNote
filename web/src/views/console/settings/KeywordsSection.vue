<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { keywords } from '../../../api/keywords'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'
import Skeleton from '../../../components/ui/Skeleton.vue'
import EmptyState from '../../../components/ui/EmptyState.vue'
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue'
import type { Keyword } from '../../../types'

const data = useDataStore()
const toast = useToast()
const kw = ref('')
const catName = ref('')
const list = ref<Keyword[]>([])
const loading = ref(true)
const loadErr = ref(false)
const saving = ref(false)

/* 删除确认（替换裸删除）：danger 态 + loading */
const pendingRemove = ref<Keyword | null>(null)
const removing = ref(false)

async function load() {
  loadErr.value = false
  try { list.value = await keywords.list() } catch { loadErr.value = true }
  finally { loading.value = false }
}
onMounted(load)

const canSubmit = computed(() => !!(kw.value.trim() && catName.value))
async function add() {
  if (!canSubmit.value) { toast.push('请填写关键词并选择分类', 'err'); return }
  saving.value = true
  try {
    await keywords.create(kw.value.trim(), catName.value)
    toast.push('已添加'); kw.value = ''; catName.value = ''; await load()
  } catch (e: any) { toast.push(e?.message || '添加失败', 'err') }
  finally { saving.value = false }
}
async function confirmRemove() {
  const k = pendingRemove.value
  if (!k) return
  removing.value = true
  try { await keywords.remove(k.id); toast.push('已删除'); pendingRemove.value = null; await load() }
  catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
  finally { removing.value = false }
}
</script>

<template>
  <Card title="关键词管理">
    <p class="hint">自定义关键词用于快速匹配分类。如"外卖"→"三餐"</p>

    <div class="add-row">
      <input v-model="kw" class="sel grow" placeholder="关键词(如:外卖)" @keyup.enter="add" />
      <select v-model="catName" class="sel"><option disabled value="">分类</option><option v-for="c in data.cats" :key="c.id" :value="c.name">{{ c.name }}</option></select>
      <Btn :disabled="!canSubmit" :loading="saving" @click="add">添加</Btn>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="loading" aria-hidden="true">
      <Skeleton v-for="i in 3" :key="i" height="14px" width="80%" />
    </div>
    <!-- 失败态 -->
    <template v-else-if="loadErr">
      <EmptyState icon="refresh" text="关键词加载失败" hint="请检查网络后重试" />
      <div class="retry-row"><Btn variant="ghost" @click="load">重试</Btn></div>
    </template>
    <!-- 空态 -->
    <EmptyState v-else-if="!list.length" icon="book" text="暂无自定义关键词" hint="添加关键词可实现智能分类匹配" />
    <!-- 正常态 -->
    <div v-else class="kw-list">
      <div v-for="k in list" :key="k.id" class="kw-row">
        <span class="kw-pair">{{ k.keyword }} → {{ k.categoryName }} <em>优先级:{{ k.priority }}</em></span>
        <button class="link danger" @click="pendingRemove = k">删除</button>
      </div>
    </div>

    <ConfirmDialog
      :open="!!pendingRemove"
      title="删除关键词"
      :message="`确定删除关键词「${pendingRemove?.keyword ?? ''}」？删除后相关账单将不再自动匹配该分类。`"
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
.hint { color: var(--muted); font-size: 13px; margin: 0 0 12px; }
.add-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.grow { flex: 1; min-width: 120px; }
.kw-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.kw-row:last-child { border-bottom: none; }
.kw-pair em { color: var(--muted); font-size: 12px; font-style: normal; margin-left: 8px; }
.link.danger { background: none; border: none; color: var(--expense); cursor: pointer; font-size: 13px; padding: 0; font-family: inherit; }
.loading { display: flex; flex-direction: column; gap: 10px; }
.retry-row { display: flex; justify-content: center; margin-top: 6px; }
</style>
