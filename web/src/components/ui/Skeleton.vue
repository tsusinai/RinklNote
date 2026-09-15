<script setup lang="ts">
/* 骨架屏基础件：shimmer 微光见 app.css 的 .ui-skeleton（1.4s 循环，非动效谱系）。
 * 数据未就绪时占位，避免布局跳动。 */
withDefaults(defineProps<{
  width?: string
  height?: string
  round?: boolean
}>(), { width: '100%', height: '14px', round: false })
</script>

<template>
  <div class="ui-skeleton" :class="{ round }" :style="{ width, height }" aria-hidden="true" />
</template>
