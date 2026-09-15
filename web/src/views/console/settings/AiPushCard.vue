<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { auth } from '../../../api/auth'
import { useToast } from '../../../composables/useToast'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'
import Skeleton from '../../../components/ui/Skeleton.vue'
import EmptyState from '../../../components/ui/EmptyState.vue'

const toast = useToast()
// 三态：null=加载中，undefined=加载失败（err 置位），boolean=正常
const disabled = ref<boolean | null | undefined>(null)
const loadErr = ref(false)

async function load() {
  loadErr.value = false
  disabled.value = null
  try { disabled.value = (await auth.aiStatus()).disabled }
  catch { loadErr.value = true }
}
onMounted(load)

async function toggle() {
  const next = !disabled.value
  try { await auth.setAiDisabled(next); disabled.value = next; toast.push(next ? 'AI 推送已关闭' : 'AI 推送已开启') }
  catch (e: any) { toast.push(e?.message || '切换失败', 'err') }
}
</script>

<template>
  <Card title="AI 主动推送">
    <p class="hint">关闭后不再收到月结/异常/习惯推送，主动问账仍可用</p>

    <!-- 加载态 -->
    <div v-if="disabled === null && !loadErr" class="loading" aria-hidden="true">
      <Skeleton height="14px" width="60%" /><Skeleton height="34px" width="120px" />
    </div>
    <!-- 失败态：EmptyState + 重试 -->
    <template v-else-if="loadErr">
      <EmptyState icon="refresh" text="加载失败" hint="无法获取 AI 推送状态" />
      <div class="retry-row"><Btn variant="ghost" @click="load">重试</Btn></div>
    </template>
    <!-- 正常态 -->
    <template v-else>
      <Btn :variant="disabled ? 'ghost' : 'primary'" @click="toggle">{{ disabled ? '已关闭' : '开启' }}</Btn>
    </template>
  </Card>
</template>

<style scoped>
/* 卡片/标题/按钮样式全部走全局与基础件，仅保留本卡说明文案排版 */
.hint { color: var(--muted); font-size: 13px; margin: 0 0 10px; }
.loading { display: flex; flex-direction: column; gap: 10px; }
.retry-row { display: flex; justify-content: center; }
</style>
