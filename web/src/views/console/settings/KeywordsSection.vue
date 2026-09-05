<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { keywords } from '../../../api/keywords'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
const data = useDataStore()
const toast = useToast()
const kw = ref('')
const catName = ref('')
const list = ref<{ id: number; keyword: string; categoryName: string; priority: number }[]>([])

async function load() { try { list.value = await keywords.list() } catch { } }
onMounted(load)

async function add() {
  if (!kw.value || !catName.value) return
  await keywords.create(kw.value, catName.value)
  toast.push('已添加'); kw.value = ''; catName.value = ''; await load()
}
async function remove(id: number) { await keywords.remove(id); await load() }
</script>

<template>
  <div class="card">
    <div class="card-title">关键词管理</div>
    <p class="hint">自定义关键词用于快速匹配分类。如"外卖"→"三餐"</p>
    <div class="add-row">
      <input class="sel grow" placeholder="关键词(如:外卖)" v-model="kw" />
      <select class="sel" v-model="catName"><option disabled value="">分类</option><option v-for="c in data.cats" :key="c.id" :value="c.name">{{ c.name }}</option></select>
      <button class="btn primary" @click="add">添加</button>
    </div>
    <div v-if="list.length" class="kw-list">
      <div v-for="k in list" :key="k.id" class="kw-row">
        <span class="kw-pair">{{ k.keyword }} → {{ k.categoryName }} <em>优先级:{{ k.priority }}</em></span>
        <button class="link danger" @click="remove(k.id)">删除</button>
      </div>
    </div>
    <div v-else class="empty">暂无自定义关键词，添加关键词可实现智能分类匹配</div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 8px; }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 12px; }
.add-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.sel { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; }
.grow { flex: 1; min-width: 120px; }
.btn.primary { padding: 10px 16px; border-radius: 10px; border: none; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; cursor: pointer; }
.kw-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.kw-pair em { color: var(--muted); font-size: 12px; font-style: normal; margin-left: 8px; }
.link.danger { background: none; border: none; color: var(--expense); cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: var(--muted); font-size: 13px; padding: 16px 0; }
</style>
