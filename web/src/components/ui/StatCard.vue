<script setup lang="ts">
/* 统计卡基础件：汇总卡（label + 大数字）统一口径，复用 app.css 的 .summary-card。
 * tone 决定数值色（收入绿/支出红）；glow 接结余呼吸光晕——
 * glow-positive=收入绿 8s 呼吸（结余≥0）/ glow-negative=支出红静态警示（结余<0），见 theme.css。 */
withDefaults(defineProps<{
  label: string
  value: string
  tone?: 'default' | 'income' | 'expense'
  glow?: 'none' | 'positive' | 'negative'
}>(), { tone: 'default', glow: 'none' })
</script>

<template>
  <div
    class="summary-card"
    :class="{ 'glow-positive': glow === 'positive', 'glow-negative': glow === 'negative' }"
  >
    <div class="label">{{ label }}</div>
    <div
      class="val amount"
      :class="{ 'text-income': tone === 'income', 'text-expense': tone === 'expense' }"
    >{{ value }}</div>
  </div>
</template>
