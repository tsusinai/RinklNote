<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const store = useAuthStore()

const tabs = [
  { name: 'bookkeeping', label: '记账' },
  { name: 'bills', label: '账单' },
  { name: 'charts', label: '图表' },
  { name: 'assets', label: '资产' },
  { name: 'me', label: '我的' },
  { name: 'settings', label: '设置' },
]
function go(name: string) { router.push({ name }) }
</script>

<template>
  <div class="console">
    <aside class="sidebar">
      <h1>RinklNote</h1>
      <nav>
        <button v-for="t in tabs" :key="t.name" :class="{ active: $route.name === t.name }" @click="go(t.name)">{{ t.label }}</button>
      </nav>
      <button class="logout" @click="store.logout(); router.push('/login')">退出</button>
    </aside>
    <main class="content"><RouterView /></main>
    <nav class="mobile-nav">
      <button v-for="t in tabs.slice(0, 5)" :key="'m' + t.name" :class="{ active: $route.name === t.name }" @click="go(t.name)">{{ t.label }}</button>
    </nav>
  </div>
</template>

<style scoped src="./console.css"></style>
