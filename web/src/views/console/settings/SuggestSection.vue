<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { insights } from '../../../api/insights'
const cfg = ref({ enabled: true, lookbackDays: 7, minOccurrences: 3, displayDuration: 5000 })
const loaded = ref(false)
const err = ref('')
async function load() {
  try { cfg.value = await insights.suggestConfig(); cfg.value ??= { enabled: true, lookbackDays: 7, minOccurrences: 3, displayDuration: 5000 } }
  catch { err.value = '加载失败，请稍后重试' }
  finally { loaded.value = true }
}
onMounted(load)
async function save() {
  try { await insights.saveSuggestConfig(cfg.value) }
  catch (e: any) { err.value = e?.message || '保存失败' }
}
</script>

<template>
  <div class="card">
    <div class="card-title">智能推荐</div>
    <div v-if="err && !loaded" class="msg err">{{ err }}</div>
    <template v-else>
      <label class="row"><input type="checkbox" v-model="cfg.enabled" @change="save" /> 启用智能推荐</label>
      <div class="slider-row"><span>回溯天数 ({{ cfg.lookbackDays }})</span><input type="range" min="3" max="30" v-model.number="cfg.lookbackDays" @change="save" /></div>
      <div class="slider-row"><span>最少出现次数 ({{ cfg.minOccurrences }})</span><input type="range" min="1" max="20" v-model.number="cfg.minOccurrences" @change="save" /></div>
      <div class="slider-row"><span>显示时长(秒) ({{ cfg.displayDuration }})</span><input type="range" min="2" max="30" v-model.number="cfg.displayDuration" @change="save" /></div>
    </template>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.slider-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 14px; }
.slider-row input { max-width: 200px; }
.msg.err { color: var(--expense); font-size: 14px; }
</style>
