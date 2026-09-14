<script setup lang="ts">
import { useToast } from '../composables/useToast'
const { toasts, close } = useToast()
</script>

<template>
  <div class="toast-wrap" aria-live="polite">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind" @click="close(t.id)">
        {{ t.msg }}
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-wrap { position: fixed; top: 16px; left: 50%; transform: translateX(-50%); z-index: 999; display: flex; flex-direction: column; gap: 8px; align-items: center; }
.toast { padding: 10px 18px; border-radius: 12px; background: var(--card); color: var(--text); box-shadow: var(--shadow); cursor: pointer; font-size: 14px; }
.toast.ok { border-left: 3px solid var(--income, #04A433); }
.toast.err { border-left: 3px solid var(--expense, #CA3032); }
.toast-enter-active, .toast-leave-active { transition: all .2s var(--ease, ease); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
