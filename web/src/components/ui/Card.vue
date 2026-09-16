<script setup lang="ts">
/* 通用卡片基础件：统一 .card 样式（背景/描边/圆角/阴影，见 app.css），
 * 替代各页面重复手写的卡片样式；标题可走 prop 或 #title 插槽自定义。 */
withDefaults(defineProps<{ title?: string }>(), { title: '' })
</script>

<template>
  <div class="card">
    <slot name="title">
      <div v-if="title" class="card-title">{{ title }}</div>
    </slot>
    <slot />
  </div>
</template>
