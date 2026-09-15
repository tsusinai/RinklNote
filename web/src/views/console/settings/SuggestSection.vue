<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { insights } from '../../../api/insights'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'
import Skeleton from '../../../components/ui/Skeleton.vue'
import EmptyState from '../../../components/ui/EmptyState.vue'

const cfg = ref({ enabled: true, lookbackDays: 7, minOccurrences: 3, displayDuration: 5000 })
const loading = ref(true)
const err = ref('')
const saving = ref(false)

async function load() {
  err.value = ''
  loading.value = true
  try {
    cfg.value = await insights.suggestConfig()
    cfg.value ??= { enabled: true, lookbackDays: 7, minOccurrences: 3, displayDuration: 5000 }
  } catch { err.value = '加载失败，请稍后重试' }
  finally { loading.value = false }
}
onMounted(load)

async function save() {
  saving.value = true
  try { await insights.saveSuggestConfig(cfg.value) }
  catch (e: any) { err.value = e?.message || '保存失败，请稍后重试' }
  finally { saving.value = false }
}
</script>

<template>
  <Card title="智能推荐">
    <!-- 加载态 -->
    <div v-if="loading" class="loading" aria-hidden="true">
      <Skeleton height="14px" width="50%" /><Skeleton height="14px" width="70%" /><Skeleton height="14px" width="65%" />
    </div>
    <!-- 失败态 -->
    <template v-else-if="err && !cfg">
      <EmptyState icon="refresh" text="加载失败" hint="无法获取智能推荐配置" />
      <div class="retry-row"><Btn variant="ghost" @click="load">重试</Btn></div>
    </template>
    <!-- 正常态（保存失败仅行内提示，不打断配置） -->
    <template v-else>
      <p v-if="err" class="msg err">{{ err }}</p>
      <label class="row check-row">
        <input type="checkbox" v-model="cfg.enabled" @change="save" /> 启用智能推荐
      </label>
      <div class="slider-row"><span>回溯天数 ({{ cfg.lookbackDays }})</span><input type="range" min="3" max="30" v-model.number="cfg.lookbackDays" @change="save" /></div>
      <div class="slider-row"><span>最少出现次数 ({{ cfg.minOccurrences }})</span><input type="range" min="1" max="20" v-model.number="cfg.minOccurrences" @change="save" /></div>
      <div class="slider-row"><span>显示时长(秒) ({{ cfg.displayDuration }})</span><input type="range" min="2" max="30" v-model.number="cfg.displayDuration" @change="save" /></div>
    </template>
  </Card>
</template>

<style scoped>
/* 卡片/标题样式走全局（Card 基础件），仅保留本卡控件排版 */
.loading { display: flex; flex-direction: column; gap: 10px; }
.retry-row { display: flex; justify-content: center; margin-top: 6px; }
.msg.err { color: var(--expense); font-size: 14px; }
.row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.check-row { cursor: pointer; }
.check-row input { accent-color: var(--primary); width: 16px; height: 16px; }
.slider-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 14px; }
.slider-row input { max-width: 200px; accent-color: var(--primary); }
</style>
