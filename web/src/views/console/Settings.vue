<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useDataStore } from '../../stores/data'
import ThemeCard from './settings/ThemeCard.vue'
import SyncCard from './settings/SyncCard.vue'
import AiPushCard from './settings/AiPushCard.vue'
import BotChannelsSection from './settings/BotChannelsSection.vue'
import KeywordsSection from './settings/KeywordsSection.vue'
import TemplatesSection from './settings/TemplatesSection.vue'
import SuggestSection from './settings/SuggestSection.vue'
import BudgetSection from './settings/BudgetSection.vue'

const route = useRoute()
const botsOnly = computed(() => route.query.section === 'bots')
const data = useDataStore()
onMounted(() => { if (!data.cats.length) data.loadData() })

// 卡片进场 stagger：每张 +60ms，封顶 360ms（配合全局 v-reveal 指令）
const delays = [0, 60, 120, 180, 240, 300, 360, 360]
</script>

<template>
  <div class="page">
    <h2>{{ botsOnly ? 'Bot 接入' : '设置' }}</h2>
    <template v-if="!botsOnly">
      <div v-reveal="delays[0]"><ThemeCard /></div>
      <div v-reveal="delays[1]"><BudgetSection /></div>
      <div v-reveal="delays[2]"><SyncCard /></div>
      <div v-reveal="delays[3]"><AiPushCard /></div>
      <div v-reveal="delays[4]"><KeywordsSection /></div>
      <div v-reveal="delays[5]"><TemplatesSection /></div>
      <div v-reveal="delays[6]"><SuggestSection /></div>
    </template>
    <div v-reveal="botsOnly ? 0 : delays[7]"><BotChannelsSection /></div>
  </div>
</template>

<style scoped>
/* 卡片纵向间距：子组件内 .card 不再各自带 margin（样式收敛到公共类），由页面统一排 */
.page > div + div { margin-top: 14px; }
</style>
