<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useThemeStore } from '../stores/theme'
import { useDataStore } from '../stores/data'
import { useToast } from '../composables/useToast'
import { toCsv } from '../utils/csv'
import { minorToDecimal } from '../utils/money'
import { fmtDateTime } from '../utils/date'
import { filterActions, nextIndex, type CommandAction } from '../utils/commands'
import Icon from './ui/Icon.vue'

/* Ctrl+K 命令面板（Task 3.4）：路由跳转 + 常用动作。
 * 动作表是配置数组（utils/commands.ts），本组件只负责打开态交互与执行器：
 * ↑↓ 选择 / Enter 执行 / Esc 关闭；打开即聚焦过滤框（焦点在面板内，全局快捷键不干扰）。 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const router = useRouter()
const theme = useThemeStore()
const data = useDataStore()
const toast = useToast()

const query = ref('')
const selected = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

const results = computed(() => filterActions(query.value))
// 选中游标随结果集收敛：过滤后越界时归位到 0
const safeSelected = computed(() =>
  results.value.length ? Math.min(selected.value, results.value.length - 1) : -1,
)

watch(() => props.open, async (open) => {
  if (!open) return
  query.value = ''
  selected.value = 0
  await nextTick()
  inputRef.value?.focus()
})

function move(delta: number) {
  selected.value = nextIndex(safeSelected.value, delta, results.value.length)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') { e.preventDefault(); emit('close'); return }
  if (e.key === 'ArrowDown') { e.preventDefault(); move(1); return }
  if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); return }
  if (e.key === 'Enter') {
    e.preventDefault()
    if (safeSelected.value >= 0) run(results.value[safeSelected.value])
  }
}

/** 导出账单 CSV：与账单页导出同列同口径（整数分 → minorToDecimal 纯整数拆分），
 *  数据取控制台缓存全集；导出动作不碰账单页本身。 */
function exportCsv() {
  if (!data.bills.length) { toast.push('暂无账单可导出', 'err'); return }
  const rows: (string | number)[][] = [['日期', '类型', '分类', '子分类', '金额', '备注', '来源']]
  for (const b of data.bills) {
    rows.push([
      fmtDateTime(b.date), b.billType === 'EXPENSE' ? '支出' : '收入', b.categoryName,
      b.subCategoryName || '', minorToDecimal(b.amountMinor), b.remark || '', b.source,
    ])
  }
  const csv = '\uFEFF' + toCsv(rows)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = 'rinklnote.csv'; a.click()
  URL.revokeObjectURL(url)
  toast.push(`已导出 ${data.bills.length} 笔账单`)
}

async function run(action: CommandAction) {
  emit('close')
  if (action.type === 'route' && action.route) {
    if (router.currentRoute.value.name !== action.route) await router.push({ name: action.route })
    return
  }
  if (action.type === 'theme') { theme.toggle(); return }
  if (action.type === 'export-csv') { exportCsv(); return }
}

/** 选项摘要：路由类展示跳转/当前页，主题类展示目标，导出类展示缓存量 */
function hintOf(a: CommandAction): string {
  if (a.type === 'route' && a.route) {
    return router.currentRoute.value.name === a.route ? '当前页' : '跳转'
  }
  if (a.type === 'theme') return theme.theme === 'dark' ? '切到亮色' : '切到暗色'
  return `${data.bills.length} 笔`
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="palette-overlay" @click.self="emit('close')">
      <div class="palette" role="dialog" aria-modal="true" aria-label="命令面板" @keydown="onKeydown">
        <div class="palette-head">
          <Icon name="search" :size="16" />
          <input
            ref="inputRef" v-model="query" class="palette-input" type="text"
            placeholder="搜索命令：记一笔 / 图表 / 导出…" aria-label="搜索命令"
            @input="selected = 0"
          />
          <kbd class="palette-kbd">Esc</kbd>
        </div>
        <ul class="palette-list" role="listbox" aria-label="命令列表">
          <li
            v-for="(a, i) in results" :key="a.id"
            role="option" :aria-selected="i === safeSelected"
            :class="['palette-item', { on: i === safeSelected }]"
            @mouseenter="selected = i" @click="run(a)"
          >
            <Icon :name="a.icon" :size="16" />
            <span class="pi-label">{{ a.label }}</span>
            <span class="pi-hint">{{ hintOf(a) }}</span>
          </li>
          <li v-if="!results.length" class="palette-empty">没有匹配的命令</li>
        </ul>
        <div class="palette-foot">↑↓ 选择 · Enter 执行 · Esc 关闭</div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.palette-overlay {
  position: fixed; inset: 0; z-index: 1000;
  background: color-mix(in srgb, var(--text) 24%, transparent);
  display: flex; justify-content: center; align-items: flex-start;
  padding: 12vh 16px 0;
}
.palette {
  width: min(520px, 100%); background: var(--card); border-radius: 16px;
  box-shadow: var(--shadow), 0 24px 64px rgba(0,0,0,.24);
  overflow: hidden; display: flex; flex-direction: column;
}
.palette-head { display: flex; align-items: center; gap: 10px; padding: 14px 16px; color: var(--muted); border-bottom: 1px solid var(--border-light); }
.palette-input { flex: 1; border: none; outline: none; background: none; font-size: 15px; color: var(--text); font-family: inherit; }
.palette-kbd { font-size: 11px; color: var(--muted); border: 1px solid var(--border); border-radius: 6px; padding: 2px 6px; }
.palette-list { list-style: none; margin: 0; padding: 6px; max-height: 46vh; overflow-y: auto; }
.palette-item {
  display: flex; align-items: center; gap: 10px; padding: 11px 12px; border-radius: 10px;
  color: var(--text); font-size: 14px; cursor: pointer;
}
.palette-item.on { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.pi-label { flex: 1; }
.pi-hint { font-size: 12px; color: var(--muted); font-weight: 400; }
.palette-empty { padding: 22px 12px; text-align: center; color: var(--muted); font-size: 13px; list-style: none; }
.palette-foot { padding: 9px 16px; border-top: 1px solid var(--border-light); color: var(--muted); font-size: 12px; }
</style>
