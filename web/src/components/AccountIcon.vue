<script setup lang="ts">
import { computed } from 'vue'
import { accountIconPath, withAlpha } from '../utils/accountIcon'

const props = withDefaults(defineProps<{
  iconKey?: string | null
  name?: string
  colorHex?: string
  size?: number
}>(), {
  iconKey: null,
  name: '',
  colorHex: '#64748B',
  size: 40,
})

const path = computed(() => accountIconPath(props.iconKey, props.name))
const background = computed(() => withAlpha(props.colorHex, 0.14))
</script>

<template>
  <span
    class="account-icon"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      color: colorHex,
      background,
    }"
  >
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path :d="path" fill="currentColor" />
    </svg>
  </span>
</template>

<style scoped>
.account-icon {
  display: inline-grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 50%;
}

.account-icon svg {
  width: 58%;
  height: 58%;
}
</style>
