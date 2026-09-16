<script setup lang="ts">
import { computed } from 'vue'

/* 通用按钮基础件：主色/幽灵/危险三态 + 按压缩放（.pressable，0.97/120ms，仅 CTA）
 * + 加载态（loading 时自旋图标并禁用，防重复提交）。 */
const props = withDefaults(defineProps<{
  variant?: 'primary' | 'ghost' | 'danger'
  block?: boolean
  disabled?: boolean
  loading?: boolean
  type?: 'button' | 'submit'
}>(), { variant: 'primary', block: false, disabled: false, loading: false, type: 'button' })

const cls = computed(() => [
  'btn', 'pressable',
  props.variant === 'danger' ? 'danger' : `btn-${props.variant}`,
  { block: props.block },
])
</script>

<template>
  <button :type="type" :class="cls" :disabled="disabled || loading">
    <span v-if="loading" class="spinner" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
/* 与全局 .btn 的 inline-block 区分：内容（含 spinner）水平居中排布 */
.btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.btn:disabled { opacity: .55; cursor: not-allowed; }
/* 自旋加载图标：循环动画不属动效谱系，独立时长 */
.spinner {
  width: 14px; height: 14px; border-radius: 50%;
  border: 2px solid currentColor; border-top-color: transparent;
  animation: ui-spin 700ms linear infinite;
}
@keyframes ui-spin { to { transform: rotate(360deg); } }
</style>
