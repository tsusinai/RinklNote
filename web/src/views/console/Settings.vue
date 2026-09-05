<script setup lang="ts">
import { onMounted } from 'vue'
import { useDataStore } from '../../stores/data'
import ThemeCard from './settings/ThemeCard.vue'
import SyncCard from './settings/SyncCard.vue'
import AiPushCard from './settings/AiPushCard.vue'
import QqBotSection from './settings/QqBotSection.vue'
import KeywordsSection from './settings/KeywordsSection.vue'
import TemplatesSection from './settings/TemplatesSection.vue'
import SuggestSection from './settings/SuggestSection.vue'
import BudgetSection from './settings/BudgetSection.vue'

const data = useDataStore()
onMounted(() => { if (!data.cats.length) data.loadData() })
</script>

<template>
  <div class="page">
    <h2>设置</h2>
    <ThemeCard />
    <BudgetSection />
    <SyncCard />
    <AiPushCard />
    <KeywordsSection />
    <TemplatesSection />
    <SuggestSection />
    <QqBotSection />
  </div>
</template>
