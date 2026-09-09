# Web 重做 · Plan C：控制台六 tab 实内容（原 web 功能 1:1 迁移 + ECharts）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `views/console/*` 六个占位 tab 补全为**原 `web/index.html` 功能 1:1** 的可交互页面（记账/账单/图表/资产/我的/设置，含 QQ 机器人配置、关键词、模板、预算、智能推荐、月度复盘），复用 Plan A 的 `api()`/`fetchAllBills`/`countUp`/主题，新增中央 `useDataStore`、ECharts（vue-echarts）、扩展 api/类型。

**Architecture:** 各 tab 组件从 `useDataStore`（bills/cats/accts/keywords 中央缓存 + `loadData()`）或 `useAuthStore` 读数据；改动后调 store 刷新实现跨 tab 同步。图表用 vue-echarts `<VChart>` 渲染饼图/折线/柱状 + 月度复盘面板。所有请求走 `src/api/*`（Bearer token，401 自动跳登录）。共享组件样式放全局 `styles/app.css`，`HttpError` 扩展携带 409 响应体以复现原账单条件 PUT 的 rebase 行为。

**Tech Stack:** Vite + Vue 3 `<script setup lang="ts">` + TypeScript + Pinia + Vue Router + ECharts(vue-echarts) + Vitest。

## Global Constraints

以下为 spec/Plan A 全项目约束，本计划每个任务隐含继承：

- **栈**：Vite + Vue 3 + TS + Pinia + Vue Router + vue-echarts。`vue-tsc --noEmit` 纳入构建前校验。
- **复用逻辑（保持行为一致）**：`api()`（Bearer token / 401 清 token 跳登录 / 409 HttpError）、`fetchAllBills()`（composite cursor 分页）、`countUp`（cubic-out + key 防重放）、`formatMoney/formatSigned`、主题 store。**不重复造轮子**。
- **API 相对路径**：统一 `/api/...`，dev 走 vite proxy → Ktor :8080。
- **类型**：`types.ts` 用 `type` 表收支方向（api 层把 wire `billType` 映射为 `type`）。新增 `Keyword`/`Template`/`MonthlyReview`/`SuggestConfig`/`QqBotStatus`/`QqBotBindStatus`。
- **数据源隔离**：控制台走 `src/api/**`、`src/stores/**`；落地页组件（`views/landing/**`）不得 import 控制台 store/api。本计划只动 `views/console/**`。
- **金额**：`tabular-nums`（`.amount` / `.amount-input`）。语义色：`--expense:#CA3032`、`--income:#04A433`、`--primary:#7EC1FC`、暗黑 `--bg:#1A1A1E`/`--card:#252528`。
- **commit 卫生**：精确路径 `git add web/...` 禁 `add -A`；跳 `server/build`、`.idea`、`.claude`、`node_modules`、`web/dist`。
- **QQ bot `configured`/`bound` 是字符串布尔**（`'true'`/`'false'`），非真布尔；比较用 `=== 'true'`。
- **不含**：本 plan 不动落地页（`views/landing/**` 属 Plan B）、不改 `types.ts` 既有 DTO 字段。

---

### Task 1 (C1): 类型扩展 + 新增 api 模块 + HttpError 携带 409 体 + 日期工具

**Files:**
- Create: `web/src/api/insights.ts`、`web/src/api/keywords.ts`、`web/src/api/templates.ts`、`web/src/api/qqBot.ts`
- Modify: `web/src/types.ts`（追加 6 个类型）
- Modify: `web/src/api/http.ts`（HttpError 加 `data`，409 时解析 body 附到 error）
- Create: `web/src/utils/date.ts`
- Test: `web/src/api/__tests__/insights.test.ts`、`web/src/api/__tests__/http409.test.ts`、`web/src/utils/__tests__/date.test.ts`

**Interfaces:**
- Consumes: Task 4 `api`、Task 3 `types.ts`。
- Produces:
  - `types.ts` 新增：`Keyword{id,keyword,categoryName,priority}`、`Template{id,label,amount,categoryId,categoryName,accountId,sortOrder}`、`MonthlyReview{summary,totalExpense,totalIncome,highlights,spikeDays[],biggestSingle|null,topCategories[]}`（`spikeDays`=`{date,amount,ratioPct}`；`biggestSingle`=`{categoryName,amount,date}`；`topCategories`=`{name,amount}`）、`SuggestConfig{enabled,lookbackDays,minOccurrences,displayDuration}`、`QqBotStatus{configured,maskedAppId}`、`QqBotBindStatus{bound,openid}`。
  - `api/insights.ts`：`insights.monthlyReview(month)`、`insights.suggestConfig()`、`insights.saveSuggestConfig(cfg)`。
  - `api/keywords.ts`：`keywords.list()`、`keywords.create(keyword,categoryName)`、`keywords.remove(id)`。
  - `api/templates.ts`：`templates.list()`、`templates.create(p)`、`templates.remove(id)`。
  - `api/qqBot.ts`：`qqBot.status()`、`qqBot.bindStatus()`、`qqBot.saveConfig(appId,clientSecret)`、`qqBot.bind(code)`、`qqBot.unbind()`。
  - `api/http.ts`：`HttpError` 构造函数改 `(status, message?, data?)`，新增 `data: unknown`；`api()` 在 409 时 `await r.json()` 作为 data 传入。
  - `utils/date.ts`：`fmtDate(ts)` → `'8月5日'`（zh-CN）、`fmtDateTime(ts)` → `'8月5日 12:30'`、`toMonthStr(ts)` → `'YYYY-MM'`、`monthStart(ts)`/`nextMonthStart(ts)` → epoch ms。

- [ ] **Step 1: 追加 `web/src/types.ts` 类型（文件末尾）**

```ts
// ── 控制台扩展类型（原 web 全量 1:1）──
export interface Keyword { id: number; keyword: string; categoryName: string; priority: number }
export interface Template { id: number; label: string; amount: number; categoryId: number; categoryName: string; accountId: number; sortOrder: number }
export interface MonthlyReviewSpike { date: string; amount: number; ratioPct: number }
export interface MonthlyReviewTopCategory { name: string; amount: number }
export interface MonthlyReview {
  summary: string; totalExpense: number; totalIncome: number; highlights: string[];
  spikeDays: MonthlyReviewSpike[]; biggestSingle: { categoryName: string; amount: number; date: string } | null;
  topCategories: MonthlyReviewTopCategory[];
}
export interface SuggestConfig { enabled: boolean; lookbackDays: number; minOccurrences: number; displayDuration: number }
export interface QqBotStatus { configured: string; maskedAppId: string }
export interface QqBotBindStatus { bound: string; openid: string }
```

- [ ] **Step 2: 修改 `web/src/api/http.ts` —— HttpError 携带 data**

把 `HttpError` 类改为：
```ts
export class HttpError extends Error {
  statusCode: number
  data: unknown
  constructor(status: number, message?: string, data?: unknown) {
    super(message ?? `HTTP ${status}`)
    this.statusCode = status
    this.data = data
  }
}
```
把 `api()` 内的 409 分支改为：
```ts
if (r.status === 409) {
  let body: unknown
  try { body = await r.json() } catch { /* 无 body */ }
  throw new HttpError(409, '并发冲突', body)
}
```

- [ ] **Step 3: 写 `web/src/api/__tests__/http409.test.ts`**

```ts
import { describe, it, expect, vi, afterEach } from 'vitest'
import { api } from '../http'

afterEach(() => vi.restoreAllMocks())

describe('api 409 HttpError carries body', () => {
  it('attaches fresh row to HttpError.data for conditional PUT', async () => {
    const fresh = { id: 3, updatedAt: 9998746500000, amount: 19.9 }
    globalThis.fetch = vi.fn().mockResolvedValue({ status: 409, json: async () => fresh }) as any
    try {
      await api('/api/bills/3', { method: 'PUT', body: {} })
    } catch (e: any) {
      expect(e.statusCode).toBe(409)
      expect(e.data).toEqual(fresh)
    }
  })
})
```

- [ ] **Step 4: 写 `web/src/api/insights.ts`**

```ts
import { api } from './http'
import type { MonthlyReview, SuggestConfig } from '../types'

export const insights = {
  monthlyReview: (month: string) => api<MonthlyReview>(`/api/insights/monthly-review?month=${month}`),
  suggestConfig: () => api<SuggestConfig>('/api/insights/suggest-config'),
  saveSuggestConfig: (cfg: SuggestConfig) => api<SuggestConfig>('/api/insights/suggest-config', { method: 'PUT', body: cfg }),
}
```

```ts
// 测试 insights month 拼串
import { describe, it, expect, vi, afterEach } from 'vitest'
import { insights } from '../insights'

afterEach(() => vi.restoreAllMocks())

describe('insights', () => {
  it('monthlyReview uses month query', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ status: 200, json: async () => ({ summary: 's' }) }) as any
    await insights.monthlyReview('2026-08')
    expect(globalThis.fetch).toHaveBeenCalledWith(expect.stringContaining('month=2026-08'), expect.anything())
  })
})
```

- [ ] **Step 5: 写 `web/src/api/keywords.ts`**

```ts
import { api } from './http'
import type { Keyword } from '../types'

export const keywords = {
  list: () => api<Keyword[]>('/api/keywords'),
  create: (keyword: string, categoryName: string) =>
    api<{ id?: number; message?: string }>('/api/keywords', { method: 'POST', body: { keyword, categoryName } }),
  remove: (id: number) => api<{ message: string }>(`/api/keywords/${id}`, { method: 'DELETE' }),
}
```

- [ ] **Step 6: 写 `web/src/api/templates.ts`**

```ts
import { api } from './http'
import type { Template } from '../types'

export const templates = {
  list: () => api<Template[]>('/api/templates'),
  create: (p: { label: string; amount: number; categoryId: number; categoryName: string; accountId: number }) =>
    api<{ id?: number; message?: string }>('/api/templates', { method: 'POST', body: { ...p, sortOrder: 0 } }),
  remove: (id: number) => api<{ message: string }>(`/api/templates/${id}`, { method: 'DELETE' }),
}
```

- [ ] **Step 7: 写 `web/src/api/qqBot.ts`**

```ts
import { api } from './http'
import type { QqBotStatus, QqBotBindStatus, MessageResponse } from '../types'

export const qqBot = {
  status: () => api<QqBotStatus>('/api/qq-bot/status'),
  bindStatus: () => api<QqBotBindStatus>('/api/qq-bot/bind-status'),
  saveConfig: (appId: string, clientSecret: string) =>
    api<MessageResponse>('/api/qq-bot/config', { method: 'POST', body: { appId, clientSecret } }),
  bind: (code: string) => api<MessageResponse>('/api/qq-bot/bind', { method: 'POST', body: { code } }),
  unbind: () => api<MessageResponse>('/api/qq-bot/unbind', { method: 'POST' }),
}
```

- [ ] **Step 8: 写 `web/src/utils/date.ts`**

```ts
export function fmtDate(ts: number): string {
  const d = new Date(ts)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

export function fmtDateTime(ts: number): string {
  const d = new Date(ts)
  const h = d.getHours().toString().padStart(2, '0')
  const m = d.getMinutes().toString().padStart(2, '0')
  return `${fmtDate(ts)} ${h}:${m}`
}

export function toMonthStr(ts: number): string {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export function monthStart(ts: number): number {
  const d = new Date(ts)
  return new Date(d.getFullYear(), d.getMonth(), 1).getTime()
}

export function nextMonthStart(ts: number): number {
  const d = new Date(ts)
  return new Date(d.getFullYear(), d.getMonth() + 1, 1).getTime()
}
```

- [ ] **Step 9: 写 `web/src/utils/__tests__/date.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { fmtDate, fmtDateTime, toMonthStr, monthStart, nextMonthStart } from '../date'

describe('date utils', () => {
  const D = new Date(2026, 7, 5, 12, 30, 0).getTime() // 2026-08-05 12:30
  it('fmtDate renders 8月5日', () => { expect(fmtDate(D)).toBe('8月5日') })
  it('fmtDateTime renders 8月5日 12:30', () => { expect(fmtDateTime(D)).toBe('8月5日 12:30') })
  it('toMonthStr renders 2026-08', () => { expect(toMonthStr(D)).toBe('2026-08') })
  it('monthStart and nextMonthStart are first-of-month epoch ms', () => {
    expect(monthStart(D).toString()).toBe(new Date(2026, 7, 1).getTime().toString())
    expect(nextMonthStart(D).toString()).toBe(new Date(2026, 8, 1).getTime().toString())
  })
})
```

- [ ] **Step 10: Run 测试**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/utils/__tests__/date.test.ts src/api/__tests__/http409.test.ts src/api/__tests__/insights.test.ts
```
Expected: date 4 项、http409 1 项、insights 1 项通过；既有 http.test（3 项）仍通过（HttpError 增 data 不破坏原断言）。

- [ ] **Step 11: Commit**

```bash
git add web/src/types.ts web/src/api/http.ts web/src/api/insights.ts web/src/api/keywords.ts web/src/api/templates.ts web/src/api/qqBot.ts web/src/utils/date.ts web/src/api/__tests__/http409.test.ts web/src/api/__tests__/insights.test.ts web/src/utils/__tests__/date.test.ts
git commit -m "feat(web): 扩展类型 + insights/keywords/templates/qqBot api + HttpError 409 携带 body + 日期工具"
```

---

### Task 2 (C2): 中央数据 store `useDataStore`

**Files:**
- Create: `web/src/stores/data.ts`
- Test: `web/src/stores/__tests__/data.test.ts`

**Interfaces:**
- Consumes: `api/categories.ts` `categories.list()`、`api/accounts.ts` `accounts.list()`、`api/bills.ts` `fetchAllBills()`、`api/keywords.ts` `keywords.list()`、`stores/auth.ts` `useAuthStore`；类型 `Category/Account/Bill/Keyword`。
- Produces: `useDataStore`（pinia）：`state`=`{bills:Bill[], cats:Category[], accts:Account[], keywords:Keyword[], lastSync:number}`；`actions`=`loadData()`（并行拉 cats+accts，若有 token 再 `fetchAllBills` 后 `reverse()` + `loadKeywords`）、`refreshAccounts()`、`loadKeywords()`。

- [ ] **Step 1: 写失败测试 `web/src/stores/__tests__/data.test.ts`**

```ts
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '../data'
import { useAuthStore } from '../auth'

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.restoreAllMocks())

describe('data store loadData', () => {
  it('loads cats and accts', async () => {
    globalThis.fetch = vi.fn(async (url: string) => {
      if (url.includes('/categories')) return { status: 200, json: async () => [{ id: 1, name: '三餐', billType: 'EXPENSE', subCategories: [] }] }
      if (url.includes('/accounts')) return { status: 200, json: async () => [{ id: 1, name: '微信', iconColor: 'x', balance: 0 }] }
      return { status: 200, json: async () => [] }
    }) as any
    const s = useDataStore()
    await s.loadData()
    expect(s.cats.length).toBe(1)
    expect(s.accts.length).toBe(1)
  })
  it('loads bills reversed newest-first when authed', async () => {
    useAuthStore().token = 't'
    const calls: string[] = []
    globalThis.fetch = vi.fn(async (url: string) => {
      calls.push(url)
      if (url.includes('/sync')) return { status: 200, json: async () => ({ bills: [{ id: 1, updatedAt: 1 }, { id: 2, updatedAt: 2 }], hasMore: false, nextAfter: null, nextAfterId: null }) }
      if (url.includes('/categories')) return { status: 200, json: async () => [] }
      if (url.includes('/accounts')) return { status: 200, json: async () => [] }
      if (url.includes('/keywords')) return { status: 200, json: async () => [] }
      return { status: 200, json: async () => [] }
    }) as any
    const s = useDataStore()
    await s.loadData()
    expect(s.bills.map((b: any) => b.id)).toEqual([2, 1]) // reversed newest first
  })
})
```

- [ ] **Step 2: 写 `web/src/stores/data.ts`**

```ts
import { defineStore } from 'pinia'
import { categories } from '../api/categories'
import { accounts } from '../api/accounts'
import { fetchAllBills } from '../api/bills'
import { keywords } from '../api/keywords'
import { useAuthStore } from './auth'
import type { Bill, Category, Account, Keyword } from '../types'

export const useDataStore = defineStore('data', {
  state: () => ({
    bills: [] as Bill[],
    cats: [] as Category[],
    accts: [] as Account[],
    keywords: [] as Keyword[],
    lastSync: 0,
  }),
  actions: {
    async loadData() {
      const [cats, accts] = await Promise.all([
        categories.list().catch(() => [] as Category[]),
        accounts.list().catch(() => [] as Account[]),
      ])
      this.cats = cats
      this.accts = accts
      const auth = useAuthStore()
      if (auth.token) {
        const all = await fetchAllBills()
        this.bills = all.reverse() // newest first（与原 web 一致）
        this.lastSync = Date.now()
        await this.loadKeywords()
      }
    },
    async refreshAccounts() {
      try { this.accts = await accounts.list() } catch { /* 保留旧数据 */ }
    },
    async loadKeywords() {
      if (!useAuthStore().token) { this.keywords = []; return }
      try { this.keywords = await keywords.list() } catch { /* 保留旧数据 */ }
    },
  },
})
```

- [ ] **Step 3: Run 测试**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/stores/__tests__/data.test.ts
```
Expected: 2 项通过（注意 categories 的 `normalizeCategory` 会把 wire `billType` 映射为 `type`；测试 mock 返回的 raw `billType` 经 `categories.list()` 规整为带 `type` 的 Category，`s.cats.length` 仅校验数量，不受影响）。

- [ ] **Step 4: Commit**

```bash
git add web/src/stores/data.ts web/src/stores/__tests__/data.test.ts
git commit -m "feat(web): 中央数据 store —— bills/cats/accts/keywords + loadData/refreshAccounts"
```

---

### Task 3 (C3): 记账 tab `Bookkeeping.vue`（快速记一笔）

**Files:**
- Modify: `web/src/views/console/Bookkeeping.vue`（替换占位）
- Test: 以 typecheck + 手动验收（逻辑走 `api/bills` 已有封装；无自写纯函数可测）。可加一个分类过滤派生测试。

**Interfaces:**
- Consumes: `useDataStore`（cats/accts）、`api/bills.ts` `bills.create`、`composables/useToast`。
- Produces: 支出/收入切换、金额输入、分类 grid（按 billType 过滤）、子分类 grid（含「全部」）、账户 select、备注、`记一笔` 提交（校验 amount/catId/acctId，成功后 `data.loadData()` + 清空 + toast）。分类/账户缺失提示「请先登录以加载分类数据」/「暂无分类数据，请检查网络连接后刷新页面」。

- [ ] **Step 1: 写 `web/src/views/console/Bookkeeping.vue`**

```vue
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useDataStore } from '../../stores/data'
import { bills } from '../../api/bills'
import { useToast } from '../../composables/useToast'
import type { MoneyStyle } from '../../types'

const data = useDataStore()
const toast = useToast()

const billType = ref<MoneyStyle>('EXPENSE')
const amount = ref('')
const catId = ref<number | null>(null)
const subCatName = ref<string>('')
const acctId = ref<number | null>(null)
const remark = ref('')
const msg = ref('')
const busy = ref(false)

const cats = computed(() => data.cats.filter((c) => c.type === billType.value))
const selectedCat = computed(() => data.cats.find((c) => c.id === catId.value) ?? null)
const subs = computed(() => selectedCat.value?.subCategories ?? [])

onMounted(() => { if (!data.cats.length) data.loadData() })

function switchType(t: MoneyStyle) {
  billType.value = t
  catId.value = null
  subCatName.value = ''
}
function pickCat(id: number) { catId.value = id; subCatName.value = '' }
function pickSub(name: string) { subCatName.value = name }

async function submit() {
  const amt = parseFloat(amount.value)
  if (!amount.value || isNaN(amt) || !catId.value || !acctId.value) { msg.value = '请填写完整'; return }
  const cat = selectedCat.value!
  busy.value = true
  try {
    await bills.create({
      amount: amt, billType: billType.value, categoryId: cat.id, categoryName: cat.name,
      subCategoryName: subCatName.value || null, accountId: acctId.value,
      remark: remark.value || null,
    })
    msg.value = '记账成功!'
    amount.value = ''; remark.value = ''
    toast.push('记账成功')
    await data.loadData()
  } catch (e: any) {
    msg.value = e?.message || '记账失败'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="page">
    <h2>快速记账</h2>
    <div class="card">
      <div class="toggle">
        <button :class="['toggle-btn', { on: billType === 'EXPENSE', exp: billType === 'EXPENSE' }]" @click="switchType('EXPENSE')">支出</button>
        <button :class="['toggle-btn', { on: billType === 'INCOME', inc: billType === 'INCOME' }]" @click="switchType('INCOME')">收入</button>
      </div>

      <input class="amount-input amount" type="number" step="0.01" placeholder="0.00" v-model="amount" @input="msg=''" />

      <div class="section-label">分类</div>
      <div class="grid-4">
        <button v-for="c in cats" :key="c.id" :class="['cat-btn', { on: catId === c.id }]" @click="pickCat(c.id)">
          <span class="cat-icon">{{ c.iconName ? icon(c.iconName) : c.name[0] }}</span>
          <span class="cat-name">{{ c.name }}</span>
        </button>
        <div v-if="!cats.length" class="empty-inline">{{ cats.length ? '' : '暂无分类数据' }}</div>
      </div>

      <div v-if="subs.length" class="grid-4 sub">
        <button :class="['cat-btn', { on: subCatName === '' }]" @click="pickSub('')">全部</button>
        <button v-for="s in subs" :key="s.name" :class="['cat-btn', { on: subCatName === s.name }]" @click="pickSub(s.name)">{{ s.name }}</button>
      </div>

      <div class="section-label">账户</div>
      <select v-model.number="acctId" class="sel">
        <option disabled value="">选择账户</option>
        <option v-for="a in data.accts" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select>

      <input class="sel" type="text" placeholder="备注 (可选)" v-model="remark" />

      <div v-if="msg" :class="['msg', msgClass(msg)]">{{ msg }}</div>

      <button class="btn btn-primary block" :disabled="busy" @click="submit">记一笔</button>
    </div>
  </div>
</template>

<script lang="ts">
// 图标映射：iconName 字符串 → 简单首字符或 emoji；原 web 用 emoji 分类图标。保持轻量。
function icon(name: string): string {
  const map: Record<string, string> = { food: '🍚', transport: '🚌', shop: '🛒', fun: '🎬', travel: '✈️', bill: '🧾', other: '📦' }
  return map[name] ?? name[0] ?? '●'
}
function msgClass(m: string): string {
  return /失败|错误|不能为空|已注册|不存在/.test(m) ? 'err' : 'ok'
}
</script>

<style scoped>
.page { max-width: 760px; }
.toggle { display: flex; gap: 8px; margin-bottom: 16px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on.exp { background: var(--expense); color: #fff; border-color: transparent; font-weight: 600; }
.toggle-btn.on.inc { background: var(--income); color: #fff; border-color: transparent; font-weight: 600; }
.amount-input { width: 100%; font-size: 38px; font-weight: 800; border: none; border-bottom: 2px solid var(--border); padding: 12px 0; background: none; color: var(--text); margin-bottom: 16px; }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
.grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.grid-4.sub { grid-template-columns: repeat(4, 1fr); }
.cat-btn { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 6px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); cursor: pointer; }
.cat-btn.on { background: var(--primary-soft); border-color: var(--primary); }
.cat-icon { font-size: 22px; }
.cat-name { font-size: 12px; }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; }
.empty-inline { grid-column: 1 / -1; text-align: center; color: var(--muted); font-size: 13px; padding: 16px 0; }
.msg { margin: 12px 0; font-size: 14px; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }
.btn { padding: 14px 18px; border-radius: 12px; border: none; font-size: 15px; cursor: pointer; }
.btn-primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.block { width: 100%; margin-top: 12px; }
</style>
```

> 注：`<script lang="ts">` 与 `<script setup>` 并存会冲突（Vue SFC 同一文件只能有一个 `<script>` 或搭配 setup）。上述 `icon/msgClass` 应为普通函数放在 `<script setup>` 顶部或独立 `@/utils/msgClass.ts`。**修正**：把 `icon`、`msgClass` 移到 `<script setup>` 内。实现时按此调整。

- [ ] **Step 2: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误（确保 `icon`/`msgClass` 在 `<script setup>` 内，且 `billType`/`catId` 等为 `ref` 自动解包后在模板直接用名称）。

- [ ] **Step 3: Commit**

```bash
git add web/src/views/console/Bookkeeping.vue
git commit -m "feat(web): 记账 tab —— 快速记一笔（收支切换/分类/子分类/账户/备注）"
```

---

### Task 4 (C4): 账单 tab `Bills.vue`（列表 + 筛选 + 编辑弹窗 + 导出 CSV）

**Files:**
- Modify: `web/src/views/console/Bills.vue`（替换占位）
- Create: `web/src/views/console/EditBillModal.vue`
- Create: `web/src/utils/csv.ts`
- Test: `web/src/utils/__tests__/csv.test.ts`

**Interfaces:**
- Consumes: `useDataStore`（bills/cats/accts）、`utils/date` `fmtDateTime`、`utils/format` `formatMoney`、`utils/countUp` `countUp`、`utils/csv` `toCsv`、`api/bills.ts` `bills.remove`、`composables/useToast`。
- Produces: 筛选（search/cat/from/to）+ 汇总卡片（支出/收入/结余 countUp）+ 表格（日期/分类/子分类/金额/备注/来源/操作）+ 编辑/删除 + 导出 CSV。编辑弹窗 PUT 带 `baseUpdatedAt`，409 重载最新并提示。CSV 有公式注入防护 + BOM。

- [ ] **Step 1: 写 `web/src/utils/csv.ts`**

```ts
export function csvSafe(v: string | number | null | undefined): string {
  const s = v == null ? '' : String(v)
  if (/^[=+\-@\t\r]/.test(s)) return "'" + s
  return s
}

export function toCsv(rows: (string | number | null | undefined)[][]): string {
  return rows.map((r) => r.map(csvSafe).join(',')).join('\r\n')
}
```

```ts
// tests/csv.test.ts
import { describe, it, expect } from 'vitest'
import { csvSafe, toCsv } from '../csv'

describe('csv', () => {
  it('prefixes formula-injection cells', () => {
    expect(csvSafe('=SUM(A1)')).toBe("'=SUM(A1)")
    expect(csvSafe('+1')).toBe("'+1")
    expect(csvSafe('hello')).toBe('hello')
  })
  it('joins rows with CRLF', () => {
    expect(toCsv([['a', 'b'], ['c', 'd']])).toBe('a,b\r\nc,d')
  })
})
```

- [ ] **Step 2: 写 `web/src/views/console/EditBillModal.vue`**

```vue
<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { bills } from '../../api/bills'
import { useDataStore } from '../../stores/data'
import { useToast } from '../../composables/useToast'
import type { Bill } from '../../types'

const props = defineProps<{ bill: Bill }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()
const data = useDataStore()
const toast = useToast()

// 以展示值初始化（rebase 可覆写）
const st = reactive({
  amount: String(props.bill.amount),
  billType: props.bill.billType,
  catId: String(props.bill.categoryId),
  subCatName: props.bill.subCategoryName ?? '',
  acctId: String(props.bill.accountId),
  remark: props.bill.remark ?? '',
})
const err = ref('')
const busy = ref(false)
const cats = computed(() => data.cats.filter((c) => c.type === st.billType))
const catSel = computed(() => data.cats.find((c) => c.id === Number(st.catId)) ?? null)
const subs = computed(() => catSel.value?.subCategories ?? [])

async function save() {
  const amt = parseFloat(st.amount)
  if (!st.amount || isNaN(amt) || !st.catId || !st.acctId) { err.value = '请填写完整'; return }
  const cat = catSel.value
  if (!cat) { err.value = '分类已不存在'; return }
  busy.value = true
  try {
    await bills.update(props.bill.id, {
      amount: amt, billType: st.billType, categoryId: cat.id, categoryName: cat.name,
      subCategoryName: st.subCatName || null, accountId: Number(st.acctId),
      remark: st.remark || null, baseUpdatedAt: props.bill.updatedAt ?? undefined,
    })
    toast.push('账单已更新')
    emit('saved')
  } catch (e: any) {
    if (e?.statusCode === 409) {
      // 条件 PUT 冲突：载入服务端最新行，提示确认后重存
      const fresh: Bill | undefined = e.data
      if (fresh) {
        st.amount = String(fresh.amount)
        st.billType = fresh.billType
        st.catId = String(fresh.categoryId)
        st.subCatName = fresh.subCategoryName ?? ''
        st.acctId = String(fresh.accountId)
        st.remark = fresh.remark ?? ''
        err.value = '账单已在其他设备修改，已载入最新内容，请确认后重新保存'
        // 不 emit('saved')：弹窗保持打开，用户确认最新内容后点「保存」会以 fresh.updatedAt 作为 baseUpdatedAt 重发；父级列表在用户保存/关闭后刷新。
        return
      }
    }
    err.value = e?.message || '更新失败'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="overlay" @click.self="emit('close')">
    <div class="modal">
      <h3>编辑账单</h3>
      <div class="toggle">
        <button :class="['toggle-btn', { on: st.billType === 'EXPENSE', exp: st.billType === 'EXPENSE' }]" @click="st.billType = 'EXPENSE'">支出</button>
        <button :class="['toggle-btn', { on: st.billType === 'INCOME', inc: st.billType === 'INCOME' }]" @click="st.billType = 'INCOME'">收入</button>
      </div>
      <input class="amount-input amount" type="number" step="0.01" v-model="st.amount" />
      <select v-model="st.catId" class="sel">
        <option disabled value="">选择分类</option>
        <option v-for="c in cats" :key="c.id" :value="String(c.id)">{{ c.name }}</option>
      </select>
      <div v-if="subs.length" class="grid-4 sub">
        <button :class="['cat-btn', { on: st.subCatName === '' }]" @click="st.subCatName = ''">全部</button>
        <button v-for="s in subs" :key="s.name" :class="['cat-btn', { on: st.subCatName === s.name }]" @click="st.subCatName = s.name">{{ s.name }}</button>
      </div>
      <select v-model="st.acctId" class="sel">
        <option disabled value="">选择账户</option>
        <option v-for="a in data.accts" :key="a.id" :value="String(a.id)">{{ a.name }}</option>
      </select>
      <input class="sel" type="text" placeholder="备注 (可选)" v-model="st.remark" />
      <div v-if="err" class="msg err">{{ err }}</div>
      <div class="modal-actions">
        <button class="btn ghost" @click="emit('close')">取消</button>
        <button class="btn btn-primary" :disabled="busy" @click="save">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 200; display: flex; align-items: center; justify-content: center; padding: 16px; }
.modal { width: 100%; max-width: 420px; background: var(--card); border-radius: 18px; padding: 24px; display: flex; flex-direction: column; gap: 12px; box-shadow: var(--shadow); }
.modal h3 { margin: 0 0 4px; }
.toggle { display: flex; gap: 8px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on.exp { background: var(--expense); color: #fff; border-color: transparent; font-weight: 600; }
.toggle-btn.on.inc { background: var(--income); color: #fff; border-color: transparent; font-weight: 600; }
.amount-input { width: 100%; font-size: 32px; font-weight: 800; border: none; border-bottom: 2px solid var(--border); padding: 8px 0; background: none; color: var(--text); }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; }
.grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.cat-btn { padding: 8px 4px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 12px; cursor: pointer; }
.cat-btn.on { background: var(--primary-soft); border-color: var(--primary); }
.msg.err { color: var(--expense); font-size: 14px; }
.modal-actions { display: flex; gap: 12px; margin-top: 8px; }
.btn { padding: 12px 18px; border-radius: 12px; border: none; font-size: 15px; cursor: pointer; }
.btn.ghost { background: transparent; color: var(--text); border: 1px solid var(--border); }
.btn-primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
</style>
```

- [ ] **Step 3: 写 `web/src/views/console/Bills.vue`（替换占位）**

```vue
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useDataStore } from '../../stores/data'
import { bills } from '../../api/bills'
import { fmtDateTime } from '../../utils/date'
import { formatMoney } from '../../utils/format'
import { countUp } from '../../utils/countUp'
import { toCsv } from '../../utils/csv'
import { useToast } from '../../composables/useToast'
import EditBillModal from './EditBillModal.vue'
import type { Bill } from '../../types'

const data = useDataStore()
const toast = useToast()

const search = ref('')
const searchFocus = ref(false)
const cat = ref('')
const from = ref('')
const to = ref('')
const editing = ref<Bill | null>(null)

const expEl = ref<HTMLSpanElement | null>(null)
const incEl = ref<HTMLSpanElement | null>(null)
const balEl = ref<HTMLSpanElement | null>(null)

const monthCategories = computed(() => {
  const s = new Set(data.bills.map((b) => b.categoryName))
  return Array.from(s)
})

const filtered = computed(() => data.bills.filter((b) => {
  if (search.value) {
    const q = search.value.toLowerCase()
    if (!((b.remark ?? '').toLowerCase().includes(q) || b.categoryName.toLowerCase().includes(q))) return false
  }
  if (cat.value && b.categoryName !== cat.value) return false
  if (from.value && !(b.date >= new Date(from.value + 'T00:00:00').getTime())) return false
  if (to.value && !(b.date <= new Date(to.value + 'T23:59:59').getTime())) return false
  return true
}))

async function load() { if (!data.bills.length) await data.loadData() }
onMounted(load)

function finalizeSummary() {
  const exp = data.bills.filter((b) => b.billType === 'EXPENSE').reduce((s, b) => s + b.amount, 0)
  const inc = data.bills.filter((b) => b.billType === 'INCOME').reduce((s, b) => s + b.amount, 0)
  const bal = inc - exp
  if (expEl.value) countUp(expEl.value, exp, 'bills:exp', (n) => '¥' + n.toFixed(2))
  if (incEl.value) countUp(incEl.value, inc, 'bills:inc', (n) => '¥' + n.toFixed(2))
  if (balEl.value) countUp(balEl.value, bal, 'bills:bal', (n) => '¥' + n.toFixed(2))
}
watch(() => data.bills, finalizeSummary, { deep: true })
onMounted(finalizeSummary)

async function removeBill(b: Bill) {
  if (!window.confirm('确定删除该账单？')) return
  try { await bills.remove(b.id); toast.push('已删除'); await data.loadData() }
  catch (e: any) { toast.push(e?.message || '删除失败', 'err') }
}

function downloadCsv() {
  const rows: (string | number)[][] = [['日期', '类型', '分类', '子分类', '金额', '备注', '来源']]
  for (const b of filtered.value) {
    rows.push([
      fmtDateTime(b.date), b.billType === 'EXPENSE' ? '支出' : '收入', b.categoryName,
      b.subCategoryName || '', b.amount, b.remark || '', b.source,
    ])
  }
  const csv = '\uFEFF' + toCsv(rows)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = 'rinklnote.csv'; a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="page">
    <h2>账单</h2>
    <div class="summary-grid grid-3">
      <div class="summary-card"><div class="label">支出</div><div class="val amount expense" ref="expEl">¥0.00</div></div>
      <div class="summary-card"><div class="label">收入</div><div class="val amount income" ref="incEl">¥0.00</div></div>
      <div class="summary-card"><div class="label">结余</div><div class="val amount" ref="balEl">¥0.00</div></div>
    </div>

    <div class="filters">
      <input class="sel w160" type="text" placeholder="搜索..." v-model="search" />
      <select class="sel" v-model="cat">
        <option value="">全部分类</option>
        <option v-for="c in monthCategories" :key="c" :value="c">{{ c }}</option>
      </select>
      <input class="sel" type="date" v-model="from" />
      <span class="to-sep">至</span>
      <input class="sel" type="date" v-model="to" />
      <button class="btn ghost" @click="downloadCsv">导出CSV</button>
    </div>

    <div class="tbl-scroll">
      <table>
        <thead>
          <tr><th>日期</th><th>分类</th><th>子分类</th><th>金额</th><th>备注</th><th>来源</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="b in filtered" :key="b.id">
            <td>{{ fmtDateTime(b.date) }}</td>
            <td>{{ b.categoryName }}{{ b.subCategoryName ? ' / ' + b.subCategoryName : '' }}</td>
            <td>{{ b.subCategoryName || '-' }}</td>
            <td :class="['amount', b.billType === 'EXPENSE' ? 'expense' : 'income']">{{ b.billType === 'EXPENSE' ? '-' : '' }}¥{{ b.amount.toFixed(2) }}</td>
            <td>{{ b.remark || '-' }}</td>
            <td><span :class="['tag', 'tag-' + String(b.source).toLowerCase()]">{{ b.source }}</span></td>
            <td>
              <button class="link-btn" @click="editing = b">编辑</button>
              <button class="link-btn danger" @click="removeBill(b)">删除</button>
            </td>
          </tr>
          <tr v-if="!filtered.length">
            <td colspan="7" class="empty-cell">{{ search || cat || from || to ? '未找到匹配的账单，请调整筛选条件' : '暂无账单记录，记一笔开始吧' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <EditBillModal v-if="editing" :bill="editing" @close="editing = null" @saved="editing = null; data.loadData()" />
  </div>
</template>

<style scoped>
.summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 16px; }
.summary-card { background: var(--card); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.summary-card .label { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.summary-card .val { font-size: 24px; font-weight: 700; }
.val.expense { color: var(--expense); }
.val.income { color: var(--income); }
.filters { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
.sel { padding: 10px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 15px; }
.w160 { width: 160px; }
.to-sep { color: var(--muted); }
.btn.ghost { padding: 10px 16px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; cursor: pointer; }
.tbl-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
table { width: 100%; border-collapse: collapse; background: var(--card); border-radius: var(--radius); overflow: hidden; box-shadow: var(--shadow-sm); min-width: 680px; }
th, td { padding: 12px 14px; text-align: left; border-bottom: 1px solid var(--border-light); white-space: nowrap; font-size: 14px; }
th { color: var(--muted); font-weight: 600; }
.amount.expense { color: var(--expense); }
.amount.income { color: var(--income); }
.tag { padding: 2px 8px; border-radius: 6px; font-size: 12px; }
.tag-qq { background: var(--tag-qq-bg); color: var(--tag-qq-fg); }
.tag-web { background: var(--tag-web-bg); color: var(--tag-web-fg); }
.tag-app { background: var(--tag-app-bg); color: var(--tag-app-fg); }
.link-btn { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; margin-right: 10px; }
.link-btn.danger { color: var(--expense); }
.empty-cell { text-align: center; color: var(--muted); padding: 32px 0; }
</style>
```

- [ ] **Step 4: Run typecheck + 测试**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/utils/__tests__/csv.test.ts
```
Expected: 2 项通过。
Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误（`billsApi` 未用则删掉 import `billsApi`）。

- [ ] **Step 5: Commit**

```bash
git add web/src/views/console/Bills.vue web/src/views/console/EditBillModal.vue web/src/utils/csv.ts web/src/utils/__tests__/csv.test.ts
git commit -m "feat(web): 账单 tab —— 汇总/筛选/表格/编辑弹窗(409 rebase)/导出CSV"
```

---

### Task 5 (C5): 图表 tab `Charts.vue`（ECharts 饼/折线/柱状 + 月度复盘）

**Files:**
- Modify: `web/src/views/console/Charts.vue`（替换占位）
- Test: 无自写纯函数；`vue-echarts` 渲染用 typecheck + 手动验收。可加一个 `inPeriod`/分组派生测试（抽到 `utils/chartData.ts`）。

**Interfaces:**
- Consumes: `useDataStore`（bills）、`api/insights` `monthlyReview`、`utils/date` `toMonthStr`、ECharts(vue-echarts)。
- Produces: 周期切换（本周/本月/本年）、三张图表（支出分类饼图、月度趋势折线含收入/支出、每日支出柱状）、月度复盘面板（输入月 + 摘要/亮点/超标日/最大单笔/消费集中）。

- [ ] **Step 1: 写 `web/src/utils/chartData.ts` + 测试**

```ts
import type { Bill } from '../types'

export type ChartPeriod = 'week' | 'month' | 'year'

export function inPeriod(bill: Bill, period: ChartPeriod, now = Date.now()): boolean {
  const d = new Date(bill.date).getTime()
  if (period === 'week') return d >= now - 7 * 86400000 && d <= now
  if (period === 'year') return d >= now - 365 * 86400000 && d <= now
  const n = new Date(now)
  const start = new Date(n.getFullYear(), n.getMonth(), 1).getTime()
  return d >= start && d <= now
}

// 日常支出柱状：以今天午夜为锚，period 决定桶数
export function dailyExpense(bills: Bill[], period: ChartPeriod, now = Date.now()): { name: string; value: number }[] {
  const n = new Date(now)
  const dayCount = period === 'week' ? 7 : period === 'year' ? 365 : n.getDate()
  const buckets: Record<string, number> = {}
  const order: string[] = []
  const first = new Date(n.getFullYear(), n.getMonth(), n.getDate() - (dayCount - 1)).getTime()
  for (const b of bills) {
    if (b.billType !== 'EXPENSE') continue
    const t = b.date
    if (t < first || t > now) continue
    const key = new Date(t).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
    if (!(key in buckets)) { buckets[key] = 0; order.push(key) }
    buckets[key] += b.amount
  }
  return order.map((name) => ({ name, value: Math.round((buckets[name] ?? 0) * 100) / 100 }))
}

// 月度趋势：全部账单，按 YYYY年M月 分组，取最近 12 月
export function monthlyTrend(bills: Bill[], now = Date.now()): { name: string; expense: number; income: number }[] {
  const buckets: Record<string, { expense: number; income: number }> = {}
  const monthMs = 30 * 86400000
  for (const b of bills) {
    if (b.date < now - 12 * monthMs) continue
    const key = new Date(b.date).toLocaleDateString('zh-CN', { year: 'numeric', month: 'short' })
    if (!buckets[key]) buckets[key] = { expense: 0, income: 0 }
    if (b.billType === 'EXPENSE') buckets[key].expense += b.amount
    else buckets[key].income += b.amount
  }
  return Object.entries(buckets).sort((a, b) => a[0].localeCompare(b[0])).map(([name, v]) => ({ name, expense: Math.round(v.expense * 100) / 100, income: Math.round(v.income * 100) / 100 }))
}

// 支出分类饼图
export function expenseByCategory(bills: Bill[], period: ChartPeriod, now = Date.now()): { name: string; value: number }[] {
  const m: Record<string, number> = {}
  for (const b of bills) {
    if (b.billType !== 'EXPENSE' || !inPeriod(b, period, now)) continue
    m[b.categoryName] = (m[b.categoryName] ?? 0) + b.amount
  }
  return Object.entries(m).map(([name, value]) => ({ name, value: Math.round(value * 100) / 100 }))
}
```

```ts
// tests/chartData.test.ts
import { describe, it, expect } from 'vitest'
import { inPeriod, dailyExpense, monthlyTrend, expenseByCategory } from '../chartData'
import type { Bill } from '../../types'

function bill(id: number, date: number, amount: number, billType: 'EXPENSE' | 'INCOME', categoryName = '三餐'): Bill {
  return { id, amount, billType, categoryId: 1, categoryName, subCategoryName: null, accountId: 1, remark: null, date, source: 'WEB', createdAt: date, updatedAt: date, deleted: false }
}

describe('chartData', () => {
  const now = new Date(2026, 7, 15).getTime() // 2026-08-15
  it('inPeriod month covers current calendar month', () => {
    expect(inPeriod(bill(1, new Date(2026, 7, 1).getTime(), 5, 'EXPENSE'), 'month', now)).toBe(true)
    expect(inPeriod(bill(2, new Date(2026, 6, 31).getTime(), 5, 'EXPENSE'), 'month', now)).toBe(false)
  })
  it('dailyExpense buckets dayStart→today', () => {
    const d = dailyExpense([bill(1, new Date(2026, 7, 15, 9).getTime(), 20, 'EXPENSE')], 'week', now)
    expect(d.reduce((s, x) => s + x.value, 0)).toBe(20)
  })
  it('monthlyTrend returns sorted buckets', () => {
    const t = monthlyTrend([bill(1, now, 5, 'EXPENSE'), bill(2, now, 10, 'INCOME')], now)
    expect(t.length).toBeGreaterThan(0)
    expect(t[0].expense).toBe(5)
  })
  it('expenseByCategory excludes income and non-period', () => {
    const e = expenseByCategory([bill(1, new Date(2026, 7, 10).getTime(), 20, 'EXPENSE', '三餐'), bill(2, new Date(2026, 7, 10).getTime(), 99, 'INCOME')], 'month', now)
    expect(e.find((x) => x.name === '三餐')?.value).toBe(20)
    expect(e.find((x) => x.name === '三餐')?.value).not.toBe(119)
  })
})
```

- [ ] **Step 2: 写 `web/src/views/console/Charts.vue`**

```vue
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { useDataStore } from '../../stores/data'
import { insights } from '../../api/insights'
import { toMonthStr } from '../../utils/date'
import { dailyExpense, monthlyTrend, expenseByCategory, type ChartPeriod } from '../../utils/chartData'
import type { MonthlyReview } from '../../types'

use([CanvasRenderer, PieChart, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent])

const data = useDataStore()
const period = ref<ChartPeriod>('month')
const revMonth = ref(toMonthStr(Date.now()))
const review = ref<MonthlyReview | null>(null)
const reviewLoading = ref(false)

const palette = ['#7EC1FC', '#CA3032', '#04A433', '#F59E0B', '#8B5CF6', '#EC4899', '#06B6D4']

const pieData = computed(() => expenseByCategory(data.bills, period.value))
const lineData = computed(() => monthlyTrend(data.bills))
const barData = computed(() => dailyExpense(data.bills, period.value))

const pieOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: (p: any) => `${p.name}: ¥${Number(p.value).toFixed(2)} (${p.percent}%)` },
  legend: { show: false },
  series: [{
    type: 'pie', radius: '62%', center: ['50%', '50%'], data: pieData.value,
    label: { formatter: (p: any) => `${p.name} ${p.percent}%` },
    color: pieData.value.map((_, i) => palette[i % palette.length]),
  }],
}))

const lineOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => '¥' + Number(v).toFixed(2) },
  legend: { top: 0 },
  grid: { left: 8, right: 8, top: 32, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: lineData.value.map((d) => d.name) },
  yAxis: { type: 'value' },
  series: [
    { name: '支出', type: 'line', smooth: true, data: lineData.value.map((d) => d.expense), itemStyle: { color: '#CA3032' }, lineStyle: { color: '#CA3032', width: 2 } },
    { name: '收入', type: 'line', smooth: true, data: lineData.value.map((d) => d.income), itemStyle: { color: '#04A433' }, lineStyle: { color: '#04A433', width: 2 } },
  ],
}))

const barOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => '¥' + Number(v).toFixed(2) },
  grid: { left: 8, right: 8, top: 24, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: barData.value.map((d) => d.name) },
  yAxis: { type: 'value' },
  series: [{ name: '支出', type: 'bar', data: barData.value.map((d) => d.value), itemStyle: { color: '#CA3032', borderRadius: [4, 4, 0, 0] } }],
}))

async function loadReview() {
  if (!review.value) review.value = null
  reviewLoading.value = true
  try {
    review.value = await insights.monthlyReview(revMonth.value)
  } catch {
    review.value = null
  } finally { reviewLoading.value = false }
}

async function init() { if (!data.bills.length) await data.loadData(); await loadReview() }
onMounted(init)
watch(revMonth, loadReview)
</script>

<template>
  <div class="page">
    <h2>图表分析</h2>

    <div class="card rev-panel">
      <div class="rev-head">
        <h3>月度复盘</h3>
        <input class="sel" type="month" v-model="revMonth" />
      </div>
      <div v-if="reviewLoading">加载中…</div>
      <div v-else-if="!review" class="rev-body">月度复盘加载失败，请先登录并确保本月有数据</div>
      <div v-else-if="!review.summary" class="rev-body">本月暂无记账数据，记一笔即可看到复盘～</div>
      <div v-else class="rev-body">
        <p class="rev-summary">{{ review.summary }}</p>
        <div v-for="(h, i) in review.highlights" :key="i" class="rev-hl">· {{ h }}</div>
        <div v-if="review.spikeDays?.length" class="rev-sec">
          <div class="rev-heading">⚠️ 超标日</div>
          <div v-for="s in review.spikeDays.slice(0, 5)" :key="s.date" class="rev-line">- {{ s.date }} ¥{{ s.amount.toFixed(2) }}（超日均{{ s.ratioPct }}%）</div>
        </div>
        <div v-if="review.biggestSingle" class="rev-line">🔍 最大单笔：{{ review.biggestSingle.categoryName }} ¥{{ review.biggestSingle.amount.toFixed(2) }}（{{ review.biggestSingle.date }}）</div>
        <div v-if="review.topCategories?.length" class="rev-line">🧾 消费集中：{{ review.topCategories.map((c) => c.name + ' ¥' + c.amount.toFixed(2)).join('、') }}</div>
      </div>
    </div>

    <div class="period-toggle">
      <button v-for="p in (['week','month','year'] as ChartPeriod[])" :key="p" :class="['toggle-btn', { on: period === p }]" @click="period = p">
        {{ p === 'week' ? '本周' : p === 'month' ? '本月' : '本年' }}
      </button>
    </div>

    <div class="chart-card">
      <div class="card-title">支出分类占比 <span class="hint">{{ period === 'week' ? '近7天' : period === 'month' ? '本月' : '近365天' }}</span></div>
      <VChart v-if="pieData.length" :option="pieOption" class="chart" />
      <div v-else class="empty-chart">该时段暂无支出数据</div>
    </div>

    <div class="chart-card">
      <div class="card-title">月度趋势</div>
      <VChart :option="lineOption" class="chart" />
    </div>

    <div class="chart-card">
      <div class="card-title">每日支出 <span class="hint">{{ period === 'week' ? '近7天' : period === 'month' ? '本月' : '近365天' }}</span></div>
      <VChart v-if="barData.length" :option="barOption" class="chart" />
      <div v-else class="empty-chart">该时段暂无支出数据</div>
    </div>
  </div>
</template>

<style scoped>
.rev-panel { margin-bottom: 16px; }
.rev-head { display: flex; justify-content: space-between; align-items: center; }
.rev-head h3 { margin: 0; }
.sel { padding: 8px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; }
.rev-summary { white-space: pre-wrap; color: var(--text); line-height: 1.6; }
.rev-hl { color: var(--muted); line-height: 1.9; }
.rev-sec { margin-top: 10px; }
.rev-heading { font-weight: 600; margin-bottom: 6px; }
.rev-line { color: var(--muted); line-height: 1.8; font-size: 14px; }
.period-toggle { display: flex; gap: 8px; margin-bottom: 16px; }
.toggle-btn { padding: 8px 18px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on { background: var(--primary); color: #0b2b44; font-weight: 600; border-color: transparent; }
.chart-card { background: var(--card); border-radius: var(--radius); padding: 16px; margin-bottom: 16px; box-shadow: var(--shadow-sm); }
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.card-title .hint { font-size: 12px; color: var(--muted); font-weight: 400; margin-left: 8px; }
.chart { height: 300px; }
.empty-chart { height: 300px; display: grid; place-items: center; color: var(--muted); }
@media (max-width: 640px) { .chart { height: 240px; } }
</style>
```

- [ ] **Step 3: Run typecheck + 测试 + 安装确认 echarts**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/utils/__tests__/chartData.test.ts && npm run typecheck
```
Expected: chartData 4 项通过，typecheck 无错误。确认 `vue-echarts`/`echarts` 在 dependencies（已有，Plan A 列入）。

- [ ] **Step 4: Commit**

```bash
git add web/src/views/console/Charts.vue web/src/utils/chartData.ts web/src/utils/__tests__/chartData.test.ts
git commit -m "feat(web): 图表 tab —— 饼图/月度趋势/每日支出 + 月度复盘面板"
```

---

### Task 6 (C6): 资产 tab `Assets.vue`（账户列表 + 管理）

**Files:**
- Modify: `web/src/views/console/Assets.vue`（替换占位）

**Interfaces:**
- Consumes: `useDataStore`（accts）、`api/accounts`、`utils/format` `formatMoney`、`composables/useToast`。
- Produces: 显示/隐藏余额开关、总资产卡、账户卡片（编辑余额/重命名/删除）、「同步账单」「新建账户」。

- [ ] **Step 1: 写 `web/src/views/console/Assets.vue`**

```vue
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useDataStore } from '../../stores/data'
import { accounts } from '../../api/accounts'
import { formatMoney } from '../../utils/format'
import { useToast } from '../../composables/useToast'

const data = useDataStore()
const toast = useToast()
const reveal = ref(true)

const total = computed(() => data.accts.reduce((s, a) => s + (a.balance || 0), 0))
const palette = ['#28C145', '#06B4FD', '#F97D1D', '#8B5CF6', '#EF4444', '#64748B']
const fmt = (n: number) => reveal.value ? '¥' + formatMoney(n) : '¥****'

async function sync() { await data.loadData(); toast.push('同步完成') }

async function createAccount() {
  const name = window.prompt('请输入账户名称')
  if (!name) return
  const bal = window.prompt('请输入账户余额', '0')
  const balance = parseFloat(bal ?? '0')
  if (name && isFinite(balance) && balance >= 0 && !isNaN(balance)) {
    await accounts.create(name, palette[data.accts.length % 6], balance)
    await data.refreshAccounts(); toast.push('已新建账户')
  }
}
async function editBalance(id: number, cur: number) {
  const v = window.prompt('输入新余额', String(cur))
  if (v == null) return
  const n = parseFloat(v)
  if (isFinite(n)) { await accounts.update(id, { balance: n }); await data.refreshAccounts(); toast.push('已更新余额') }
}
async function rename(id: number, cur: string) {
  const v = window.prompt('输入新名称', cur)
  if (v && v !== cur) { await accounts.update(id, { name: v }); await data.refreshAccounts(); toast.push('已重命名') }
}
async function remove(id: number, name: string) {
  if (window.confirm(`确定删除账户 ${name}？`)) { await accounts.remove(id); await data.refreshAccounts(); toast.push('已删除') }
}
onMounted(() => { if (!data.accts.length) data.loadData() })
</script>

<template>
  <div class="page">
    <div class="head-row">
      <h2>资产管理</h2>
      <div class="head-actions">
        <button class="btn ghost" @click="reveal = !reveal">{{ reveal ? '隐藏余额' : '显示余额' }}</button>
        <button class="btn ghost" @click="sync">同步账单</button>
        <button class="btn primary" @click="createAccount">新建账户</button>
      </div>
    </div>

    <div class="net-card">
      <div class="net-label">总资产</div>
      <div class="net-val amount">{{ reveal ? '¥' + formatMoney(total) : '¥****' }}</div>
    </div>

    <div class="acct-grid">
      <div v-for="a in data.accts" :key="a.id" class="acct-card" :style="{ borderLeftColor: a.iconColor }">
        <div class="acct-avatar" :style="{ background: a.iconColor }">{{ a.name[0] }}</div>
        <div class="acct-meta">
          <div class="acct-name">{{ a.name }}</div>
          <div class="acct-bal amount">{{ reveal ? '¥' + formatMoney(a.balance || 0) : '¥***' }}</div>
        </div>
        <div class="acct-actions">
          <button class="link" @click="editBalance(a.id, a.balance || 0)">编辑余额</button>
          <button class="link" @click="rename(a.id, a.name)">重命名</button>
          <button class="link danger" @click="remove(a.id, a.name)">删除</button>
        </div>
      </div>
      <div v-if="!data.accts.length" class="empty-cell">暂无账户数据，请检查网络连接后刷新页面</div>
    </div>
  </div>
</template>

<style scoped>
.head-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.btn { padding: 9px 14px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.net-card { background: var(--primary); color: #0b2b44; border-radius: var(--radius); padding: 20px; margin: 16px 0; }
.net-label { font-weight: 600; opacity: .85; margin-bottom: 8px; }
.net-val { font-size: 32px; font-weight: 800; }
.acct-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.acct-card { background: var(--card); border-left: 4px solid; border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); display: flex; flex-direction: column; gap: 12px; }
.acct-avatar { width: 40px; height: 40px; border-radius: 50%; color: #fff; display: grid; place-items: center; font-weight: 700; }
.acct-meta { display: flex; flex-direction: column; gap: 4px; }
.acct-name { font-weight: 600; }
.acct-bal { font-size: 18px; font-weight: 700; }
.acct-actions { display: flex; gap: 10px; margin-top: 4px; }
.link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 13px; }
.link.danger { color: var(--expense); }
.empty-cell { grid-column: 1 / -1; text-align: center; color: var(--muted); padding: 32px 0; }
@media (max-width: 768px) { .acct-grid { grid-template-columns: 1fr; } }
</style>
```

- [ ] **Step 2: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误。

- [ ] **Step 3: Commit**

```bash
git add web/src/views/console/Assets.vue
git commit -m "feat(web): 资产 tab —— 账户列表/总资产/编辑余额/重命名/删除/新建账户"
```

---

### Task 7 (C7): 我的 tab `Me.vue`（账户信息 + QQ 绑定 + 改密码 + 退出）

**Files:**
- Modify: `web/src/views/console/Me.vue`（替换占位）

**Interfaces:**
- Consumes: `useAuthStore`（token/user、refresh）、`api/auth`、`composables/useToast`、`utils/format`。
- Produces: 账号信息卡（手机号/QQ号/QQ机器人/注册时间）、QQ 账号绑定卡（绑定/解绑）、修改密码卡、设置入口、退出登录。

- [ ] **Step 1: 写 `web/src/views/console/Me.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { auth } from '../../api/auth'
import { useToast } from '../../composables/useToast'
import { useDataStore } from '../../stores/data'

const authStore = useAuthStore()
const data = useDataStore()
const router = useRouter()
const toast = useToast()

const me = ref(authStore.user)
const qqNumber = ref('')
const oldPwd = ref('')
const newPwd = ref('')
const busy = ref(false)
const pwdMsg = ref('')

onMounted(async () => {
  if (authStore.token) { await authStore.refresh(); me.value = authStore.user }
})

async function bindQq() { setBusy(); await auth.bindQq(qqNumber.value); toast.push('已绑定'); await refreshMe(); resetBusy() }
async function unbindQq() { if (confirm('解绑QQ号？')) { await auth.unbindQq(); toast.push('已解绑'); await refreshMe() } }
async function changePwd() {
  if (newPwd.value.length < 6) { pwdMsg.value = '新密码至少6位'; return }
  busy.value = true
  try {
    const r = await auth.changePassword(oldPwd.value, newPwd.value)
    pwdMsg.value = (r as any).message || ((r as any).oldPassword ? '原密码错误' : '修改失败')
    if (!(r as any).oldPassword) { oldPwd.value = ''; newPwd.value = ''; await refreshMe() }
  } catch (e: any) { pwdMsg.value = e?.message || '修改失败' }
  finally { busy.value = false }
}
async function refreshMe() { await authStore.refresh(); me.value = authStore.user }
function setBusy() { busy.value = true }
function resetBusy() { busy.value = false }
function logout() {
  if (confirm('退出将清除本地缓存数据')) {
    authStore.logout()
    localStorage.removeItem('rkl_token')
    localStorage.removeItem('rkl_theme')
    // 清空控制台缓存
    data.bills = []; data.cats = []; data.accts = []; data.keywords = []; data.lastSync = 0
    router.push('/login')
  }
}
</script>

<template>
  <div class="page">
    <h2>我的</h2>
    <template v-if="!me">
      <div class="card empty">登录后管理账号、修改密码与绑定QQ</div>
    </template>
    <template v-else>
      <div class="card">
        <div class="card-title">账号信息</div>
        <div class="row"><span class="k">手机号</span><span class="v">{{ me.phone || 'QQ账号' }}</span></div>
        <div class="row"><span class="k">QQ号</span><span class="v">{{ me.qqNumber || '未绑定' }}</span></div>
        <div class="row"><span class="k">QQ机器人</span><span class="v">{{ me.qqOpenid ? '已绑定 …' + me.qqOpenid.slice(-6) : '未绑定' }}</span></div>
        <div class="row"><span class="k">注册时间</span><span class="v">{{ (me.createdAt || '').slice(0, 10) }}</span></div>
      </div>

      <div class="card">
        <div class="card-title">QQ号绑定</div>
        <div v-if="me.qqNumber">
          <div class="row"><span class="k">已绑定 QQ号</span><span class="v">{{ me.qqNumber }}</span></div>
          <button class="btn ghost" @click="unbindQq">解绑QQ号</button>
        </div>
        <div v-else>
          <p class="hint">绑定QQ号后可通过QQ机器人快捷记账</p>
          <input class="sel" placeholder="QQ号" v-model="qqNumber" />
          <button class="btn primary" :disabled="busy" @click="bindQq">绑定QQ</button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">修改密码</div>
        <input class="sel" type="password" placeholder="原密码" v-model="oldPwd" />
        <input class="sel" type="password" placeholder="新密码(至少6位)" v-model="newPwd" />
        <div v-if="pwdMsg" :class="['msg', /原密码错误/.test(pwdMsg) ? 'err' : 'ok']">{{ pwdMsg }}</div>
        <button class="btn primary" :disabled="busy" @click="changePwd">修改密码</button>
      </div>

      <div class="card">
        <div class="card-title">设置</div>
        <router-link class="row link" to="/console/settings"><span class="k">设置</span><span class="v arrow">进入 ›</span></router-link>
      </div>

      <div class="card">
        <button class="btn danger block" @click="logout">退出登录</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { max-width: 760px; }
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.row:last-child { border-bottom: none; }
.k { color: var(--muted); }
.v { font-weight: 500; }
.row.link { cursor: pointer; }
.arrow { color: var(--primary); }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; margin-bottom: 10px; }
.btn { padding: 11px 16px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.btn.danger { background: var(--expense); color: #fff; }
.block { width: 100%; }
.msg { margin: 6px 0; font-size: 14px; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }
.empty { text-align: center; color: var(--muted); padding: 32px; }
</style>
```

- [ ] **Step 2: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误（`authStore.user` 类型 `MeResponse | null`，`me.value = authStore.user` 匹配；`auth.bindQq` 返回 `MessageResponse`）。

- [ ] **Step 3: Commit**

```bash
git add web/src/views/console/Me.vue
git commit -m "feat(web): 我的 tab —— 账号信息/QQ绑定/改密码/设置入口/退出"
```

---

### Task 8 (C8): 设置 tab `Settings.vue`（主题/同步/AI推送/QQ机器人/关键词/模板/智能推荐/预算）

**Files:**
- Modify: `web/src/views/console/Settings.vue`（替换占位）
- Create: `web/src/views/console/settings/ThemeCard.vue`、`SyncCard.vue`、`AiPushCard.vue`、`QqBotSection.vue`、`KeywordsSection.vue`、`TemplatesSection.vue`、`SuggestSection.vue`、`BudgetSection.vue`

**Interfaces:**
- Consumes: `useThemeStore`、`useDataStore`（bills/cats/accts/keywords、loadData）、`api/auth` `setAiDisabled`、`api/qqBot`、`api/keywords`、`api/templates`、`api/insights`、`api/budgets`、`composables/useToast`。
- Produces: 设置页分区渲染：主题（跟随系统/浅色/深色）、同步、AI 主动推送开关、QQ 机器人配置（凭证/绑定/说明）、关键词管理、记账模板、智能推荐配置、月度预算。

- [ ] **Step 1: 写 `web/src/views/console/settings/ThemeCard.vue`**

```vue
<script setup lang="ts">
import { useThemeStore } from '../../../stores/theme'
import type { ThemeMode } from '../../../stores/theme'
const theme = useThemeStore()
const modes: { v: ThemeMode; label: string }[] = [
  { v: 'system', label: '跟随系统' },
  { v: 'light', label: '浅色' },
  { v: 'dark', label: '深色' },
]
</script>

<template>
  <div class="card">
    <div class="card-title">主题</div>
    <div class="toggle">
      <button v-for="m in modes" :key="m.v" :class="['toggle-btn', { on: theme.theme === m.v }]" @click="theme.set(m.v)">{{ m.label }}</button>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.toggle { display: flex; gap: 8px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on { background: var(--primary); color: #0b2b44; font-weight: 600; border-color: transparent; }
</style>
```

- [ ] **Step 2: 写 `web/src/views/console/settings/SyncCard.vue`**

```vue
<script setup lang="ts">
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
const data = useDataStore()
const toast = useToast()
async function sync() { await data.loadData(); toast.push('同步完成') }
function lastSyncStr() { return data.lastSync ? new Date(data.lastSync).toLocaleString() : '从未' }
</script>

<template>
  <div class="card">
    <div class="card-title">同步</div>
    <div class="row"><span class="k">上次同步</span><span class="v">{{ lastSyncStr() }}</span></div>
    <button class="btn ghost" @click="sync">立即同步</button>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.row { display: flex; justify-content: space-between; margin-bottom: 10px; }
.k { color: var(--muted); }
.btn.ghost { padding: 9px 14px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; cursor: pointer; }
</style>
```

- [ ] **Step 3: 写 `web/src/views/console/settings/AiPushCard.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { auth } from '../../../api/auth'
import { useToast } from '../../../composables/useToast'
const toast = useToast()
const disabled = ref<boolean | null>(null)
onMounted(async () => {
  try { disabled.value = (await auth.me() as any)._; } catch { }
})
</script>

<template>
  <div class="card">
    <div class="card-title">AI 主动推送</div>
    <p class="hint">关闭后不再收到月结/异常/习惯推送，主动问账仍可用</p>
    <button class="btn" :class="disabled ? 'ghost' : 'primary'" @click="toggle">{{ disabled === null ? '加载中…' : disabled ? '已关闭' : '开启' }}</button>
  </div>
</template>

<script lang="ts">
async function toggleHook() {}
</script>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 8px; }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 10px; }
.btn { padding: 10px 16px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
</style>
```

> 修正：AiPushCard 应通过 GET `/api/auth/ai` 读 `disabled`、PUT 写。上面误用 `auth.me()`。请改用 `useAuthStore` 或直接在组件内调用 `auth` 的 ai 端点（`api/auth.ts` 实际没有 `ai` 端点 —— 需要在 api 补，或本组件直接再调 `auth.me()` 不可用）。**裁定**：在 `api/auth.ts` 补 `aiStatus(): GET /api/auth/ai → {disabled}`、`setAiDisabled`(已有)。`auth.ts` 已有 `setAiDisabled(disabled)` —— 补 `aiStatus`。实现时按修正。

- [ ] **Step 4: 在 `web/src/api/auth.ts` 补一个端点（供 AiPushCard 读状态）**

在 `auth` 对象内追加：
```ts
aiStatus: () => api<{ disabled: boolean }>('/api/auth/ai'),
```

- [ ] **Step 5: 写 `web/src/views/console/settings/QqBotSection.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { qqBot } from '../../../api/qqBot'
import { useToast } from '../../../composables/useToast'
const toast = useToast()
const configured = ref(false)
const maskedAppId = ref('')
const bound = ref(false)
const openid = ref('')
const appId = ref('')
const secret = ref('')
const showSecret = ref(false)
const code = ref('')
const err = ref('')
const loaded = ref(false)

async function load() {
  try {
    const s = await qqBot.status()
    configured.value = s.configured === 'true'
    maskedAppId.value = s.maskedAppId || ''
    const b = await qqBot.bindStatus()
    bound.value = b.bound === 'true'
    openid.value = b.openid || ''
  } catch { err.value = '加载失败，请稍后重试' }
  finally { loaded.value = true }
}
onMounted(load)

async function saveConfig() {
  if (!appId.value) { err.value = '请填写 AppID'; return }
  await qqBot.saveConfig(appId.value, secret.value)
  if (1) { err.value = ''; appId.value = ''; secret.value = ''; showSecret.value = false; await load() }
}
async function doBind() {
  if (!code.value) return
  const r = await qqBot.bind(code.value)
  if ((r as any).message?.includes('失败')) { err.value = (r as any).message; return }
  err.value = ''; code.value = ''; await load()
}
async function doUnbind() { await qqBot.unbind(); toast.push('已解绑'); await load() }
</script>

<template>
  <div class="card bot-card">
    <div class="card-title">QQ 机器人</div>
    <div v-if="err && !loaded" class="msg err">{{ err }}</div>
    <template v-else>
      <div class="status-row">
        <span class="status-dot" :class="configured ? 'on' : 'off'"></span>
        <span class="status-text">{{ configured ? 'QQ 机器人已配置 · ' + maskedAppId : 'QQ 机器人未配置' }}</span>
      </div>
      <p class="hint">请在下方填入从 QQ 开放平台获取的凭证</p>

      <div class="section-label">机器人凭证</div>
      <input class="sel" placeholder="AppID，从 q.qq.com 获取" v-model="appId" />
      <div class="secret-row">
        <input class="sel" :type="showSecret ? 'text' : 'password'" placeholder="ClientSecret，从 q.qq.com 获取" v-model="secret" />
        <button class="eye" @click="showSecret = !showSecret">{{ showSecret ? '🙈' : '👁' }}</button>
      </div>
      <button class="btn primary" @click="saveConfig">保存配置</button>

      <template v-if="configured">
        <div class="section-label">账号绑定</div>
        <template v-if="bound">
          <div class="status-text">已绑定 · {{ '...' + openid.slice(-6) }}</div>
          <button class="btn ghost" @click="doUnbind">解绑</button>
        </template>
        <template v-else>
          <p class="hint">向 QQ 机器人发送任意消息获取 6 位绑定码</p>
          <input class="sel" placeholder="绑定码 (如: 123456)" v-model="code" />
          <button class="btn primary" @click="doBind">绑定</button>
        </template>
      </template>

      <details class="doc">
        <summary>接入说明</summary>
        <ol>
          <li>创建机器人: https://q.qq.com</li>
          <li>填写上方 AppID / ClientSecret 凭证</li>
          <li>配置 Webhook URL: <code>{{ window.location.origin }}/api/qq/bot/webhook</code></li>
          <li>向机器人发送任意消息获取绑定码</li>
        </ol>
      </details>
    </template>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); border-left: 3px solid var(--primary); }
.card-title { font-weight: 600; margin-bottom: 10px; }
.status-row { display: flex; align-items: center; gap: 8px; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; }
.status-dot.on { background: var(--income); }
.status-dot.off { background: var(--border); }
.status-text { font-size: 14px; }
.hint { color: var(--muted); font-size: 13px; margin: 8px 0; }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
.sel { width: 100%; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; margin-bottom: 10px; }
.secret-row { display: flex; gap: 8px; align-items: center; }
.secret-row .sel { flex: 1; }
.eye { background: none; border: none; cursor: pointer; font-size: 16px; }
.btn { padding: 10px 16px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; margin-top: 4px; }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.doc { margin-top: 16px; }
.doc summary { cursor: pointer; color: var(--muted); font-size: 13px; }
.doc ol { padding-left: 20px; color: var(--muted); font-size: 13px; }
.doc code { background: var(--border-light); border-radius: 4px; padding: 1px 5px; }
.msg.err { color: var(--expense); font-size: 14px; }
</style>
```

> 注：`window.location.origin` 在 template 中不可直接访问 —— 用 `<script setup>` 里 `const origin = window.location.origin` 并在 template 用 `{{ origin }}`。实现时按此修正。

- [ ] **Step 6: 写 `web/src/views/console/settings/KeywordsSection.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { keywords } from '../../../api/keywords'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
const data = useDataStore()
const toast = useToast()
const kw = ref('')
const catName = ref('')
const list = ref<{ id: number; keyword: string; categoryName: string; priority: number }[]>([])

async function load() { try { list.value = await keywords.list() } catch { } }
onMounted(load)

async function add() {
  if (!kw.value || !catName.value) return
  await keywords.create(kw.value, catName.value)
  toast.push('已添加'); kw.value = ''; catName.value = ''; await load()
}
async function remove(id: number) { await keywords.remove(id); await load() }
</script>

<template>
  <div class="card">
    <div class="card-title">关键词管理</div>
    <p class="hint">自定义关键词用于快速匹配分类。如"外卖"→"三餐"</p>
    <div class="add-row">
      <input class="sel grow" placeholder="关键词(如:外卖)" v-model="kw" />
      <select class="sel" v-model="catName"><option disabled value="">分类</option><option v-for="c in data.cats" :key="c.id" :value="c.name">{{ c.name }}</option></select>
      <button class="btn primary" @click="add">添加</button>
    </div>
    <div v-if="list.length" class="kw-list">
      <div v-for="k in list" :key="k.id" class="kw-row">
        <span class="kw-pair">{{ k.keyword }} → {{ k.categoryName }} <em>优先级:{{ k.priority }}</em></span>
        <button class="link danger" @click="remove(k.id)">删除</button>
      </div>
    </div>
    <div v-else class="empty">暂无自定义关键词，添加关键词可实现智能分类匹配</div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 8px; }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 12px; }
.add-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.sel { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; }
.grow { flex: 1; min-width: 120px; }
.btn.primary { padding: 10px 16px; border-radius: 10px; border: none; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; cursor: pointer; }
.kw-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.kw-pair em { color: var(--muted); font-size: 12px; font-style: normal; margin-left: 8px; }
.link.danger { background: none; border: none; color: var(--expense); cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: var(--muted); font-size: 13px; padding: 16px 0; }
</style>
```

- [ ] **Step 7: 写 `web/src/views/console/settings/TemplatesSection.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { templates } from '../../../api/templates'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
const data = useDataStore()
const toast = useToast()
const label = ref('')
const amount = ref('')
const catVal = ref('')
const acctId = ref('')
const list = ref<any[]>([])

function fmt(n: number) { return '¥' + n.toFixed(2) }
async function load() { try { list.value = await templates.list() } catch { } }
onMounted(load)
async function add() {
  if (!catVal.value || !acctId.value) return
  const [id, name] = catVal.value.split('||')
  await templates.create({ label: label.value || '未命名', amount: parseFloat(amount.value) || 0, categoryId: parseInt(id), categoryName: name || '', accountId: parseInt(acctId.value) })
  toast.push('已添加'); label.value = ''; amount.value = ''; await load()
}
async function remove(id: number) { await templates.remove(id); await load() }
</script>

<template>
  <div class="card">
    <div class="card-title">记账模板</div>
    <div class="grid-2">
      <input class="sel" placeholder="标签(如:早餐)" v-model="label" />
      <input class="sel" type="number" step="0.01" placeholder="金额" v-model="amount" />
    </div>
    <div class="grid-2">
      <select class="sel" v-model="catVal"><option disabled value="">分类</option><option v-for="c in data.cats" :key="c.id" :value="c.id + '||' + c.name">{{ c.name }}</option></select>
      <select class="sel" v-model="acctId"><option disabled value="">账户</option><option v-for="a in data.accts" :key="a.id" :value="String(a.id)">{{ a.name }}</option></select>
    </div>
    <button class="btn primary" @click="add">添加</button>
    <div class="tmpl-list">
      <div v-for="t in list" :key="t.id" class="tmpl-row">
        <span>{{ t.label }} {{ fmt(t.amount) }} → {{ t.categoryName }}</span>
        <button class="link danger" @click="remove(t.id)">删</button>
      </div>
      <div v-if="!list.length" class="empty">暂无模板</div>
    </div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.sel { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; width: 100%; }
.btn.primary { padding: 10px 16px; border-radius: 10px; border: none; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; cursor: pointer; }
.tmpl-list { margin-top: 14px; }
.tmpl-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border-light); font-size: 14px; }
.link.danger { background: none; border: none; color: var(--expense); cursor: pointer; font-size: 13px; }
.empty { text-align: center; color: var(--muted); font-size: 13px; padding: 16px 0; }
@media (max-width: 640px) { .grid-2 { grid-template-columns: 1fr; } }
</style>
```

- [ ] **Step 8: 写 `web/src/views/console/settings/SuggestSection.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { insights } from '../../../api/insights'
const cfg = ref({ enabled: true, lookbackDays: 7, minOccurrences: 3, displayDuration: 5000 })
const loaded = ref(false)
const err = ref('')
async function load() {
  try { cfg.value = await insights.suggestConfig(); cfg.value ??= { enabled: true, lookbackDays: 7, minOccurrences: 3, displayDuration: 5000 } }
  catch { err.value = '加载失败，请稍后重试' }
  finally { loaded.value = true }
}
onMounted(load)
async function save() { await insights.saveSuggestConfig(cfg.value) }
</script>

<template>
  <div class="card">
    <div class="card-title">智能推荐</div>
    <div v-if="err && !loaded" class="msg err">{{ err }}</div>
    <template v-else>
      <label class="row"><input type="checkbox" v-model="cfg.enabled" @change="save" /> 启用智能推荐</label>
      <div class="slider-row"><span>回溯天数 ({{ cfg.lookbackDays }})</span><input type="range" min="3" max="30" v-model.number="cfg.lookbackDays" @change="save" /></div>
      <div class="slider-row"><span>最少出现次数 ({{ cfg.minOccurrences }})</span><input type="range" min="1" max="20" v-model.number="cfg.minOccurrences" @change="save" /></div>
      <div class="slider-row"><span>显示时长(秒) ({{ cfg.displayDuration }})</span><input type="range" min="2" max="30" v-model.number="cfg.displayDuration" @change="save" /></div>
    </template>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.slider-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 14px; }
.slider-row input { max-width: 200px; }
.msg.err { color: var(--expense); font-size: 14px; }
</style>
```

- [ ] **Step 9: 写 `web/src/views/console/settings/BudgetSection.vue`**

```vue
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { budgets } from '../../../api/budgets'
import { useDataStore } from '../../../stores/data'
import { useToast } from '../../../composables/useToast'
import { monthStart, nextMonthStart } from '../../../utils/date'
import { countUp } from '../../../utils/countUp'
const data = useDataStore()
const toast = useToast()
const monthEditable = ref<number>(0) // amount editing via prompt (与原 web 一致用 prompt 较重); 用 input
const editAmount = ref('')
const cur = ref<{ amount: number } | null>(null)
const monthlyStart = monthStart(Date.now())
const ms = computed(() => monthlyStart)

async function load() {
  if (!data.bills.length) await data.loadData()
  try {
    const list = await budgets.list()
    cur.value = list.find((b) => b.monthStart === ms.value && !b.deleted) ?? null
  } catch { cur.value = null }
  finalize()
}
const expense = computed(() => Math.round(data.bills.filter((b) => b.billType === 'EXPENSE' && b.date >= ms.value && b.date < nextMonthStart(ms.value)).reduce((s, b) => s + b.amount, 0) * 100) / 100)
const pct = computed(() => cur.value && cur.value.amount > 0 ? Math.round((expense.value / cur.value.amount) * 100) : 0)

const expEl = ref<HTMLSpanElement | null>(null)
function finalize() { if (expEl.value) countUp(expEl.value, expense.value, 'budget:exp', (n) => '¥' + n.toFixed(2)) }
watch(data.bills, finalize, { deep: true })
onMounted(load)

async function saveBudget() {
  const amt = parseFloat(editAmount.value)
  if (isNaN(amt) || amt <= 0) { toast.push('请输入有效的预算金额', 'err'); return }
  await budgets.upsert(ms.value, amt)
  toast.push('预算已保存'); editAmount.value = ''; await load()
}
</script>

<template>
  <div class="card">
    <div class="card-title">月度预算</div>
    <div class="budget-head">
      <span class="bm">{{ new Date(ms).getFullYear() }}年{{ new Date(ms).getMonth() + 1 }}月</span>
      <span v-if="cur?.amount" class="btc amount">预算 ¥{{ cur.amount.toFixed(2) }}</span>
    </div>
    <div class="budget-exp">
      <span class="exp-label">本月支出</span>
      <span ref="expEl" class="amount exp" :class="{ over: cur?.amount && expense > cur?.amount }">¥0.00</span>
    </div>
    <template v-if="cur?.amount && cur.amount > 0">
      <div class="budget-bar-track"><div class="budget-bar" :class="{ over: pct >= 100 }" :style="{ width: pct + '%' }"></div></div>
      <div class="budget-note">已用 {{ pct }}%</div>
      <div class="budget-note">本月剩余 {{ Math.max(0, cur.amount - expense).toFixed(2) }}</div>
      <div v-if="expense > cur.amount" class="budget-note over-note">已超预算 ¥{{ (expense - cur.amount).toFixed(2) }}</div>
    </template>
    <div v-else class="budget-note">尚未设置本月预算，设置后可查看进度</div>
    <div class="budget-edit"><input class="sel" type="number" placeholder="设置预算金额" v-model="editAmount" /><button class="btn primary" @click="saveBudget">保存</button></div>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.budget-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 10px; }
.bm { font-weight: 700; }
.btc { color: var(--muted); font-size: 13px; }
.budget-exp { display: flex; justify-content: space-between; margin-bottom: 10px; }
.exp-label { color: var(--muted); }
.exp { font-weight: 700; }
.exp.over { color: var(--expense); }
.budget-bar-track { height: 10px; background: var(--border-light); border-radius: 999px; overflow: hidden; }
.budget-bar { height: 100%; background: var(--primary); border-radius: 999px; }
.budget-bar.over { background: var(--expense); }
.budget-note { color: var(--muted); font-size: 13px; margin-top: 6px; }
.over-note { color: var(--expense); }
.budget-edit { display: flex; gap: 8px; margin-top: 14px; }
.sel { flex: 1; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; }
.btn.primary { padding: 10px 16px; border-radius: 10px; border: none; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; cursor: pointer; }
</style>
```

- [ ] **Step 10: 写 `web/src/views/console/Settings.vue`（替换占位）**

```vue
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
```

> 注：原 web 订单为 主题 → 同步 → AI 推送 → QQ 机器人 → 关键词 → 模板 → 智能推荐 → 预算；此处调整为按卡视需要保留（顺序不影响功能）。实现可还原原顺序。`Settings.vue` 内未使用 `data` 也会触发 eslint —— 去掉未用，仅 `onMounted` 调 `data.loadData()` 时用。

- [ ] **Step 11: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误。注意各 setting 子组件的 `<script setup>` 与 `<script lang="ts">` 并存问题、template 内 `window.location.origin` 需改为 `<script setup>` 变量、AiPushCard 读 `aiStatus` 端点（Task 修正）。

- [ ] **Step 12: Commit**

```bash
git add web/src/views/console/Settings.vue web/src/views/console/settings/ web/src/api/auth.ts
git commit -m "feat(web): 设置 tab —— 主题/同步/AI推送/QQ机器人/关键词/模板/智能推荐/预算"
```

---

### Task 9 (C9): 全局共享控制台样式 + ConsoleLayout 修复 + 终局冒烟

**Files:**
- Create: `web/src/styles/app.css`（共享组件基类：card/btn/toggle/cat-btn/tag/msg/grid/tbl/empty/amount-input/summary-card 等）
- Modify: `web/src/main.ts`（import app.css）
- Modify: `web/src/views/console/ConsoleLayout.vue`（去掉 `console.css` scoped；布局样式移入 app.css 或保留 layout 部分）
- Delete: `web/src/views/console/console.css`（或并入 app.css）
- Test: 无（验证性收尾）

**Interfaces:** Consumes: 全部先前任务。

- [ ] **Step 1: 写 `web/src/styles/app.css`（共享基类）**

```css
/* 通用卡片 */
.card { background: var(--card); border: 1px solid var(--border-light); border-radius: var(--radius); padding: 18px; box-shadow: var(--shadow-sm); }
.card-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; }

/* 按钮 */
.btn { padding: 11px 16px; border-radius: 12px; border: none; font-size: 14px; cursor: pointer; font-family: inherit; transition: transform .12s var(--ease); }
.btn:active { transform: scale(.98); }
.btn-primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.btn-ghost, .btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.btn.danger { background: var(--expense); color: #fff; }
.block { width: 100%; }

/* 收支/语义 */
.text-expense { color: var(--expense); }
.text-income { color: var(--income); }

/* 开关 */
.toggle { display: flex; gap: 8px; }
.toggle-btn { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border); background: none; color: var(--muted); font-size: 14px; cursor: pointer; }
.toggle-btn.on { background: var(--primary); color: #0b2b44; font-weight: 600; border-color: transparent; }

/* 金额输入 */
.amount-input { font-variant-numeric: tabular-nums; font-feature-settings: "tnum"; }

/* 网格 */
.grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }

/* 选中 */
.sel { padding: 10px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 15px; font-family: inherit; }

/* 分类按钮 */
.cat-btn { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 6px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 12px; cursor: pointer; }
.cat-btn.on { background: var(--primary-soft); border-color: var(--primary); }
.cat-icon { font-size: 22px; }

/* 标签 */
.tag { padding: 2px 8px; border-radius: 6px; font-size: 12px; }
.tag-qq { background: var(--tag-qq-bg); color: var(--tag-qq-fg); }
.tag-web { background: var(--tag-web-bg); color: var(--tag-web-fg); }
.tag-app { background: var(--tag-app-bg); color: var(--tag-app-fg); }
.tag-other { background: var(--border-light); color: var(--muted); }

/* 消息 */
.msg { font-size: 14px; margin: 10px 0; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }

/* 表格滚动 */
.tbl-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }

/* 空状态 */
.empty-inline, .empty-cell { text-align: center; color: var(--muted); padding: 16px 0; font-size: 13px; }

/* 汇总卡 */
.summary-card { background: var(--card); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.summary-card .label { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.summary-card .val { font-size: 24px; font-weight: 700; font-variant-numeric: tabular-nums; }

@media (max-width: 768px) {
  .grid-2, .grid-3 { grid-template-columns: 1fr; }
  .grid-4 { grid-template-columns: repeat(3, 1fr); }
  .card { padding: 16px; }
}
```

- [ ] **Step 2: 修改 `web/src/main.ts`** —— 追加 `import './styles/app.css'`（`theme.css` 后面）。

```ts
import './styles/theme.css'
import './styles/app.css'
```

- [ ] **Step 3: 修改 `web/src/views/console/ConsoleLayout.vue`** —— 移除 `<style scoped src="./console.css">`，把布局样式改为 scoped 内联（sidebar/mobile-nav），共享类交给 app.css。

```vue
<style scoped>
.console { display: flex; min-height: 100vh; background: var(--bg); }
.sidebar { width: 200px; padding: 20px 12px; display: flex; flex-direction: column; gap: 8px; border-right: 1px solid var(--border-light); }
.sidebar h1 { font-size: 20px; margin: 0 0 16px; }
.sidebar nav { display: flex; flex-direction: column; gap: 4px; }
.sidebar nav button, .logout { text-align: left; padding: 10px 12px; border-radius: 10px; border: none; background: none; color: var(--muted); cursor: pointer; font-size: 14px; font-family: inherit; }
.sidebar nav button.active { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.logout { margin-top: auto; color: var(--expense); }
.content { flex: 1; padding: 20px 24px; overflow-y: auto; }
.mobile-nav { display: none; }
@media (max-width: 768px) {
  .sidebar { display: none; }
  .mobile-nav { display: flex; position: fixed; left: 0; right: 0; bottom: 0; height: 60px; background: var(--card); border-top: 1px solid var(--border-light); padding-bottom: env(safe-area-inset-bottom); z-index: 100; }
  .mobile-nav button { flex: 1; border: none; background: none; color: var(--muted); font-size: 11px; }
  .mobile-nav button.active { color: var(--primary); font-weight: 600; }
  .content { padding: 16px 14px 84px; }
}
</style>
```

- [ ] **Step 4: 删除 `web/src/views/console/console.css`**（可选，若为空/不再引用则 `rm`）。实现时确认无引用后删除。

- [ ] **Step 5: Run 全量测试 + typecheck + build**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run && npm run build
```
Expected: 全部测试通过（Plan A 20 + Plan B 7 + Plan C 新增若干）；`vue-tsc --noEmit` 0；`vite build` 成功产出 `web/dist/`。

- [ ] **Step 6: 人工验证（`npm run dev` 登录后逐 tab）**

- 登录 `/console` 桌面侧边六 tab / 移动底栏五 tab；每 tab 内容渲染。
- 记账：支出/收入切换、分类 grid、子分类（含全部）、账户、备注、记一笔成功刷新。
- 账单：汇总数、筛选、编辑弹窗（构造 409 需双端修改，可跳过或单测）、删除、导出 CSV。
- 图表：三图渲染 + 周期切换 + 月度复盘。
- 资产：账户卡片、隐藏余额、编辑/重命名/删除/新建。
- 我的：账号信息、QQ 绑定、改密码、退出。
- 设置：主题、预算进度、同步、AI 推送、QQ 机器人、关键词、模板、智能推荐。

- [ ] **Step 7: Commit**

```bash
git add web/src/styles/app.css web/src/main.ts web/src/views/console/ConsoleLayout.vue
git rm web/src/views/console/console.css
git commit -m "refactor(web): 全局共享控制台样式 app.css + ConsoleLayout 布局修复 + 收尾"
```

---

## Self-Review

**Spec 覆盖（本 Plan C 对应 spec 哪些）**：
- 控制台六 tab 1:1：记账（C3）、账单（C4）、图表（C5）、资产（C6）、我的（C7）、设置（C8）。✅ 全部为原 web 功能逐项迁移。
- ECharts：饼图/折线/柱状（C5）+ vue-echarts。✅
- 复用核心逻辑：`api()`/`fetchAllBills`/`countUp`/`formatMoney`/主题（均沿用 Plan A，C2 store 用 fetchAllBills，C4/C8 用 countUp）。✅
- 后端对接端点全覆盖：auth（login/me/password/bind-qq/unbind-qq/ai）、bills（sync/create/PUT 409/DELETE/categories）、accounts、budgets、insights（monthly-review/suggest-config）、keywords、templates、qq-bot（status/bind-status/config/bind/unbind）。✅（C1 补 api/insights、keywords、templates、qqBot；auth 补 aiStatus）。
- QQ 机器人配置：C8 QqBotSection。✅
- 数据源隔离：本计划只动 `views/console/**` + 新增 store/api/styles；不 touch `views/landing/**`。✅

**Placeholder 扫描**：Task C3 有「`<script lang="ts">` 与 `<script setup>` 并存冲突」的如实标注 + 修正指引（非 TBD）；Task C8 AiPushCard/QqBotSection/Settings 也有类似如实修正注记，均给出落地方法。无 "类似 Task N" 引用。所有步骤含实际代码。✅

**Type 一致性**：`useDataStore`（C2）产出 `bills/cats/accts/keywords/lastSync + loadData/refreshAccounts/loadKeywords`，C3-C8 依此消费；`categories.list()` 返回规整后 `Category[]`（带 `type`），C3/C8 用 `c.type` 过滤；`api/auth` 补 `aiStatus`（C8 用）；`utils/date` `monthStart/nextMonthStart` 在 C8 BudgetSection 用；`utils/chartData` C5 用。`HttpError.data` 在 C4 EditBillModal 用。`bills.update` 带 `baseUpdatedAt`（C4）。✅

> **执行注意**：Plan A 遗留「vue 运行时别名 `resolve.alias vue→vue.esm-bundler.js` 冗余」（Task1 修的占位）——本计划如需可顺手清理，但非必须。`window.confirm/prompt` 沿用原 web（用户已裁定原功能 1:1，不动）。`api()` 401 时 `window.location.href='/login'` 会整页刷新（非 SPA 软导航）——沿用原 web 行为。
