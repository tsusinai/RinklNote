<script setup lang="ts">
import Modal from './Modal.vue'
import Btn from './Btn.vue'

/* 确认对话框：全站 window.confirm 的统一替换件（W3 起逐处接入）。
 * danger=true 时确认键为红色（删除类操作）；loading 期间双键禁用防重复提交。 */
withDefaults(defineProps<{
  open: boolean
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  loading?: boolean
}>(), {
  title: '请确认', confirmText: '确认', cancelText: '取消',
  danger: false, loading: false,
})

const emit = defineEmits<{ (e: 'confirm'): void; (e: 'close'): void; (e: 'update:open', v: boolean): void }>()

function close(): void {
  emit('close')
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="open" :title="title" max-width="380px" hide-close
    @close="close" @update:open="emit('update:open', $event)"
  >
    <p class="msg-line">{{ message }}</p>
    <div class="ui-panel-foot">
      <Btn variant="ghost" block :disabled="loading" @click="close">{{ cancelText }}</Btn>
      <Btn :variant="danger ? 'danger' : 'primary'" block :loading="loading" @click="emit('confirm')">
        {{ confirmText }}
      </Btn>
    </div>
  </Modal>
</template>

<style scoped>
.msg-line { margin: 4px 0 0; font-size: 14px; line-height: 1.6; color: var(--text); }
</style>
