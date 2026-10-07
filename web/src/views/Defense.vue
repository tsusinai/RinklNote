<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './defense/DefenseIcon.vue'
import DefenseSlide from './defense/DefenseSlide.vue'
import { pageFromQuery, slides } from './defense/deck'
import './defense/defense.css'

const route = useRoute()
const router = useRouter()
const current = computed(() => pageFromQuery(route.query.page))
const slide = computed(() => slides[current.value]!)
const notes = ref(false)
const presenting = ref(false)
const fullscreen = ref(false)
const elapsed = ref(0)
const running = ref(false)
const message = ref('')
const overview = ref<HTMLDialogElement>()
const root = ref<HTMLElement>()
const strip = ref<HTMLElement>()
let tick: ReturnType<typeof setInterval> | undefined
let startedAt = 0
let accumulated = 0
const time = computed(() => `${String(Math.floor(elapsed.value / 60)).padStart(2, '0')}:${String(elapsed.value % 60).padStart(2, '0')}`)
function go(index: number) {
  if (index < 0 || index >= slides.length) return
  overview.value?.close()
  void router.replace({ query: { ...route.query, page: String(index + 1) } })
}
function toggleTimer() {
  if (running.value) { accumulated += Date.now() - startedAt; running.value = false }
  else { startedAt = Date.now(); running.value = true }
}
function resetTimer() { accumulated = 0; elapsed.value = 0; if (running.value) startedAt = Date.now() }
async function togglePresent() {
  presenting.value = !presenting.value
  if (presenting.value && !running.value) toggleTimer()
  if (presenting.value) {
    try { if (root.value?.requestFullscreen) await root.value.requestFullscreen() }
    catch { message.value = '已进入演示布局；浏览器未允许全屏。' }
  } else if (document.fullscreenElement) {
    try { await document.exitFullscreen() } catch { message.value = '可按 Esc 退出浏览器全屏。' }
  }
}
function syncFullscreen() {
  const active = !!document.fullscreenElement
  if (fullscreen.value && !active) presenting.value = false
  fullscreen.value = active
}
function keyboard(event: KeyboardEvent) {
  if (event.altKey || event.ctrlKey || event.metaKey || overview.value?.open) return
  const target = event.target as HTMLElement | null
  if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
  if (['ArrowRight', 'PageDown'].includes(event.key)) { event.preventDefault(); go(current.value + 1) }
  else if (['ArrowLeft', 'PageUp'].includes(event.key)) { event.preventDefault(); go(current.value - 1) }
  else if (event.key === 'Home') { event.preventDefault(); go(0) }
  else if (event.key === 'End') { event.preventDefault(); go(11) }
  else if (event.key.toLowerCase() === 'n') notes.value = !notes.value
  else if (event.key === 'Escape' && presenting.value && !document.fullscreenElement) presenting.value = false
}
watch(current, async () => { await nextTick(); strip.value?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' }) })
onMounted(() => {
  document.addEventListener('keydown', keyboard)
  document.addEventListener('fullscreenchange', syncFullscreen)
  tick = setInterval(() => { if (running.value) elapsed.value = Math.floor((accumulated + Date.now() - startedAt) / 1000) }, 250)
})
onBeforeUnmount(() => { document.removeEventListener('keydown', keyboard); document.removeEventListener('fullscreenchange', syncFullscreen); clearInterval(tick) })
function printDeck() { window.print() }
</script>
<template>
  <div ref="root" class="defense-app" :class="{ 'is-presenting': presenting }">
    <aside class="defense-sidebar">
      <RouterLink to="/" class="defense-brand"><span class="brand-mark"><Icon name="book" :size="24" /></span><span>RinklNote<small>记一笔 · 项目答辩</small></span></RouterLink>
      <div class="sidebar-heading"><span>演示文稿</span><span>12 页</span></div>
      <nav aria-label="答辩章节"><button v-for="(item, index) in slides" :key="item.title" :class="{ selected: index === current }" :aria-current="index === current ? 'page' : undefined" @click="go(index)"><span>{{ String(index + 1).padStart(2, '0') }}</span><b>{{ item.title }}</b><i v-if="index === current"></i></button></nav>
      <div class="sidebar-bottom"><div class="sidebar-tip"><Icon name="leaf" :size="20" /><p>把复杂留给技术，<br>把简单留给生活。</p></div><RouterLink to="/">项目首页 <Icon name="arrow" :size="15" /></RouterLink><div class="sidebar-version"><span class="status-dot"></span> 答辩演示版 <span>2026</span></div></div>
    </aside>
    <main class="defense-main">
      <header class="defense-toolbar"><div class="toolbar-breadcrumb"><Icon name="book" :size="17" /><span>项目答辩</span><span>/</span><b>{{ slide.title }}</b></div><div class="toolbar-actions"><button class="d-button overview-button" @click="overview?.showModal()"><Icon name="grid" :size="16" /><span>总览</span></button><button class="d-button print-button" @click="printDeck"><Icon name="print" :size="16" /><span>导出 / 打印</span></button><button class="d-button d-primary" @click="togglePresent"><Icon :name="presenting ? 'close' : 'play'" :size="15" /><span>{{ presenting ? '结束演示' : '开始演示' }}</span></button></div></header>
      <div class="defense-workspace"><div class="workspace-heading"><div><span class="status-dot"></span><b>RinklNote 项目答辩</b><span class="workspace-subtitle">从一笔记录，看见更好的生活</span></div><span>{{ String(current + 1).padStart(2, '0') }} <span class="light-text">/ 12</span></span></div>
        <div class="slide-frame"><DefenseSlide :key="current" :index="current" @next="go(current + 1)" @jump="go" /></div>
        <div class="playback-bar"><div><Icon name="clock" :size="16" /><span class="timer">{{ time }}</span><button class="icon-button" :aria-label="running ? '暂停计时' : '开始计时'" :title="running ? '暂停计时' : '开始计时'" @click="toggleTimer"><Icon :name="running ? 'pause' : 'play'" :size="14" /></button><button class="icon-button" aria-label="重置计时" title="重置计时" @click="resetTimer"><Icon name="sync" :size="14" /></button></div><div class="page-controls"><button class="icon-button" :disabled="current === 0" aria-label="上一页" @click="go(current - 1)"><Icon name="left" :size="19" /></button><span>{{ current + 1 }} <span class="light-text">/ 12</span></span><button class="icon-button" :disabled="current === 11" aria-label="下一页" @click="go(current + 1)"><Icon name="right" :size="19" /></button></div><div><button class="d-button notes-toggle" :aria-expanded="notes" aria-controls="speaker-notes" @click="notes = !notes"><Icon name="note" :size="16" />演讲备注</button><button class="icon-button" :aria-label="presenting ? '退出演示模式' : '全屏演示'" @click="togglePresent"><Icon name="expand" :size="17" /></button></div></div>
        <section v-if="notes" id="speaker-notes" class="speaker-notes"><b>{{ String(current + 1).padStart(2, '0') }} · {{ slide.title }}</b><p>{{ slide.note }}</p></section>
        <div v-if="message" class="defense-message" role="status">{{ message }}<button class="icon-button" aria-label="关闭提示" @click="message = ''"><Icon name="close" :size="14" /></button></div>
        <section class="filmstrip-section" aria-label="页面缩略导航"><div class="filmstrip-label"><span>演示路线 <small>完整的故事，分十二页展开</small></span><button class="text-button" @click="overview?.showModal()">查看全部 <Icon name="grid" :size="13" /></button></div><div ref="strip" class="filmstrip"><button v-for="(item, index) in slides" :key="item.title" class="filmstrip-item" :class="{ selected: index === current }" :aria-current="index === current ? 'page' : undefined" :aria-label="`跳转第 ${index + 1} 页：${item.title}`" @click="go(index)"><div class="mini-slide" :class="`mini-type-${index % 4}`"><span>RinklNote · 记一笔</span><b>{{ item.heading.split('\n')[0] }}</b><div class="mini-art"><i></i><i></i><i></i></div></div><span class="filmstrip-caption"><small>{{ String(index + 1).padStart(2, '0') }}</small>{{ item.title }}</span></button></div></section>
        <footer class="workspace-footer"><span><Icon name="leaf" :size="13" /> 用心记录，让生活有数。</span><span>使用 <kbd>←</kbd> <kbd>→</kbd> 翻页 <i>·</i> <kbd>N</kbd> 查看备注</span></footer>
      </div>
    </main>
    <dialog ref="overview" class="defense-overview" aria-labelledby="overview-title" @click="($event.target === overview) && overview?.close()"><header><div><span class="eyebrow">十二页，一个完整的故事</span><h2 id="overview-title">选择一个章节</h2></div><button class="icon-button" aria-label="关闭总览" @click="overview?.close()"><Icon name="close" /></button></header><div class="overview-grid"><button v-for="(item, index) in slides" :key="item.title" :class="{ selected: index === current }" @click="go(index)"><span>{{ String(index + 1).padStart(2, '0') }}</span><b>{{ item.title }}</b><small>{{ item.section }}</small></button></div></dialog>
    <div class="defense-print"><DefenseSlide v-for="(_, index) in slides" :key="index" :index="index" /></div>
  </div>
</template>
