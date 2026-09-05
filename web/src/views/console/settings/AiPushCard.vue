<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { auth } from '../../../api/auth'
import { useToast } from '../../../composables/useToast'
const toast = useToast()
const disabled = ref<boolean | null>(null)
onMounted(async () => { try { disabled.value = (await auth.aiStatus()).disabled } catch { /* 加载失败保持 null */ } })
async function toggle() {
  const next = !disabled.value
  await auth.setAiDisabled(next)
  disabled.value = next
  toast.push(next ? 'AI 推送已关闭' : 'AI 推送已开启')
}
</script>

<template>
  <div class="card">
    <div class="card-title">AI 主动推送</div>
    <p class="hint">关闭后不再收到月结/异常/习惯推送，主动问账仍可用</p>
    <button class="btn" :class="disabled ? 'ghost' : 'primary'" :disabled="disabled === null" @click="toggle">{{ disabled === null ? '加载中…' : disabled ? '已关闭' : '开启' }}</button>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 8px; }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 10px; }
.btn { padding: 10px 16px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.btn:disabled { opacity: .6; cursor: default; }
</style>
