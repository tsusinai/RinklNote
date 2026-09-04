# Web 重做 · Plan A：脚手架 + 类型 + 核心逻辑 + 登录 + 控制台骨架 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Vite + Vue 3 + TypeScript 的 web 工程骨架，落地「登录闭环 + 路由守卫 + 核心逻辑层（api 封装 / 金额与 countUp / 主题 / 数据加载）+ 控制台空壳」，为 Plan B(落地页)、Plan C(控制台六 tab) 提供地基。

**Architecture:** 单 SPA，Vue Router 三路由（`/` 落地页、`/login`、`/console` 控制台守卫）。控制台逻辑全部走 `src/api/` 真后端封装（`Authorization: Bearer`），复用原 `web/index.html` 已验证逻辑（`api()` 401 跳登录、`fetchAllBills` composite cursor 分页、`countUp` cubic-out 动效、`applyTheme` data-theme 主题）。本 plan 不实现落地页与六 tab 内容页，只让它们可路由到空壳。

**Tech Stack:** Vite + Vue 3 `<script setup lang="ts">` + TypeScript + Pinia + Vue Router + Vitest（测试）。ECharts 仅列为依赖，Plan C 才用。

## Global Constraints

以下为 spec 全项目约束，每个任务隐含继承：

- **栈**：Vite + Vue 3 `<script setup lang="ts">` + TypeScript + Pinia + Vue Router + ECharts(vue-echarts)。`tsconfig` + `vue-tsc --noEmit` 纳入构建前校验。
- **相对路径 API**：前端统一用相对路径 `/api/...`（`API=''`）。开发走 `vite.config.ts` 的 `server.proxy` `/api` → Ktor `:8080`；生产同源反代。
- **token 存 localStorage，key 为 `rkl_token`**；`Authorization: Bearer <token>`。
- **401 行为**：`api()` 检测到 401 且已有 token → 清 token/localStorage → 跳 `/login`。
- **主题**：`data-theme` 取 `light`/`dark`，为空则用 `prefers-color-scheme`；localStorage key `rkl_theme`（`system`/`light`/`dark`）。语义色沿用 App：`--expense:#CA3032`、`--income:#04A433`、`--primary:#7EC1FC`、暗黑 `--bg:#1A1A1E`/`--card:#252528`。
- **金额**：`tabular-nums`（`font-variant-numeric`）。`countUp` 用 cubic-out 缓动（`eased=1-pow(1-t,3)`），带 `key` 防重放。
- **数据源隔离**：落地页只 import `src/mock/demo-data.ts`（Mock）；控制台走 `src/api/` 真后端。落地页不得 import 控制台 store。
- **服务端 DTO 基线（camelCase wire）**：`BillDTO`=`{id,amount,billType,categoryId,categoryName,subCategoryName?,accountId,remark?,date,source,createdAt,updatedAt?,deleted}`；`SyncResponse`=`{bills[],serverTime,hasMore,nextAfter?,nextAfterId?}`；`CategoryDTO`=`{id,name,iconName,billType,subCategories[]}`；`AccountDTO`(主仓库当前)=`{id,name,balance,iconColor,updatedAt,deleted}`；`BudgetDTO`=`{id,monthStart,amount,createdAt,updatedAt?,deleted}`；`AuthResponse`=`{userId,token}`；`MeResponse`=`{id,phone?,qqNumber?,qqOpenid?,createdAt?,aiDisabled}`。
- **commit 卫生**：精确路径 `git add <path>`，禁 `add -A`；跳 `server/build`、`.idea`、`.claude`、`node_modules`。web 新工程在 `web/` 下自建 `node_modules` 不入库（gitignore）。

---

### Task 1: Vite + Vue 3 + TS 交付脚手架

**Files:**
- Create: `web/package.json`
- Create: `web/vite.config.ts`
- Create: `web/tsconfig.json`
- Create: `web/tsconfig.node.json`
- Create: `web/env.d.ts`
- Create: `web/index.html`
- Create: `web/src/main.ts`
- Create: `web/src/App.vue`
- Create: `web/src/router/index.ts`（占位路由，真正守卫在 Task 7 再加）
- Create: `web/.gitignore`
- Test: 无（脚手架以 `vite build` 通过为验收）

**Interfaces:**
- Consumes: 无。
- Produces: `web/src/main.ts` 的 `createApp(App).use(router).use(pinia).mount('#app')`；`router` 暴露 `/`、`/login`、`/console` 三条占位路由（`views/` 占位组件在 Task 7/8 落成；本任务先用手写内联空组件）。

- [ ] **Step 1: 写 `web/package.json`**

```json
{
  "name": "rinklnote-web",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "typecheck": "vue-tsc --noEmit",
    "test": "vitest run"
  },
  "dependencies": {
    "echarts": "^5.5.1",
    "pinia": "^2.2.6",
    "vue": "^3.5.13",
    "vue-echarts": "^7.0.3",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1",
    "typescript": "^5.6.3",
    "vite": "^6.0.3",
    "vitest": "^2.1.8",
    "vue-tsc": "^2.1.10"
  }
}
```

- [ ] **Step 2: 写 `web/vite.config.ts`**

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // Control console talks to the Ktor API on the same relative path.
      // Dev: Vite proxies /api → Ktor :8080. Prod: same-origin reverse proxy.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
```

- [ ] **Step 3: 写 `web/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ESNext",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "esModuleInterop": true,
    "lib": ["ESNext", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "types": ["vite/client"],
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] }
  },
  "include": ["src/**/*.ts", "src/**/*.d.ts", "src/**/*.vue", "env.d.ts"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 4: 写 `web/tsconfig.node.json`**

```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "types": ["node"]
  },
  "include": ["vite.config.ts"]
}
```

- [ ] **Step 5: 写 `web/env.d.ts`**

```ts
/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
```

- [ ] **Step 6: 写 `web/index.html`**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>RinklNote</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 7: 写 `web/src/main.ts`**

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

createApp(App).use(createPinia()).use(router).mount('#app')
```

- [ ] **Step 8: 写 `web/src/App.vue`**

```vue
<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <RouterView />
</template>
```

- [ ] **Step 9: 写 `web/src/router/index.ts`（占位，守卫 Task 7 加）**

```ts
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'landing', component: { template: '<div>落地页（待建）</div>' } },
  { path: '/login', name: 'login', component: { template: '<div>登录（待建）</div>' } },
  { path: '/console', name: 'console', component: { template: '<div>控制台（待建）</div>' } },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
```

- [ ] **Step 10: 写 `web/.gitignore`**

```
node_modules/
dist/
*.local
```

- [ ] **Step 11: 安装依赖并验证 build**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm install
```
Expected: 若 `npm registry` 可达则装成；若因网络被阻断失败，停止并报告（先确认 `registry.npmjs.org` 可达，`curl -s -o /dev/null -w "%{http_code}" https://registry.npmjs.org/` 应为 200，注意本机仅 `github.com` 被阻断）。

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run build
```
Expected: `vue-tsc --noEmit` 通过 + `vite build` 产出 `web/dist/index.html` 等（3 条占位路由仅内联组件，无 TS 错误）。

- [ ] **Step 12: Commit**

```bash
git add web/package.json web/vite.config.ts web/tsconfig.json web/tsconfig.node.json web/env.d.ts web/index.html web/src/main.ts web/src/App.vue web/src/router/index.ts web/.gitignore
git commit -m "feat(web): Vite+Vue3+TS 脚手架，三条占位路由，dev 代理 /api→:8080"
```

---

### Task 2: 核心纯函数 —— 金额格式化 + countUp

**Files:**
- Create: `web/src/utils/format.ts`
- Create: `web/src/utils/countUp.ts`
- Test: `web/src/utils/__tests__/format.test.ts`
- Test: `web/src/utils/__tests__/countUp.test.ts`

**Interfaces:**
- Consumes: 无。
- Produces: `formatMoney(n: number): string`（千分位、2 位小数、负号在前，如 `-1,234.50`）；`formatSigned(n: number): string`（支出带 `-`、收入带 `+`）；`countUp(el: {textContent:string}, target:number, key:string|null, fmt:(n:number)=>string, dur?:number): void`（cubic-out 缓动，`key` 命中且值未变则直接 `el.textContent=fmt(target)` 防重放）。

- [ ] **Step 1: 写失败测试 `web/src/utils/__tests__/format.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { formatMoney, formatSigned } from '../format'

describe('formatMoney', () => {
  it('formats with thousands separator and 2 decimals', () => {
    expect(formatMoney(1234.5)).toBe('1,234.50')
    expect(formatMoney(0)).toBe('0.00')
    expect(formatMoney(-1234.5)).toBe('-1,234.50')
  })
  it('dedupes sign on negative expense', () => {
    expect(formatSigned(-1234.5)).toBe('-1,234.50')
    expect(formatSigned(1234.5)).toBe('+1,234.50')
  })
})
```

- [ ] **Step 2: 写失败测试 `web/src/utils/__tests__/countUp.test.ts`**

```ts
import { describe, it, expect, vi } from 'vitest'
import { countUp } from '../countUp'

describe('countUp', () => {
  it('skips to final value when key already at target', () => {
    const el = { textContent: '' }
    countUp(el, 100, 'a', String, 0)
    countUp(el, 100, 'a', String, 0)
    expect(el.textContent).toBe('100')
  })
  it('reaches final value', () => {
    vi.useFakeTimers()
    const el = { textContent: '' }
    countUp(el, 50, 'b', (n) => String(Math.round(n)), 0)
    vi.runAllTimers()
    expect(el.textContent).toBe('50')
    vi.useRealTimers()
  })
})
```

- [ ] **Step 3: Run 验证失败**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/utils/__tests__/format.test.ts src/utils/__tests__/countUp.test.ts
```
Expected: 在写实现前跑会失败（模块不存在 / 断言失败）。若模块不存在导致收集失败，记录为预期失败。

- [ ] **Step 4: 写 `web/src/utils/format.ts`**

```ts
export function formatMoney(n: number): string {
  const neg = n < 0
  const abs = Math.abs(n).toFixed(2)
  const [int, dec] = abs.split('.')
  const withSep = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (neg ? '-' : '') + withSep + '.' + dec
}

export function formatSigned(n: number): string {
  const s = formatMoney(Math.abs(n))
  return n < 0 ? '-' + s : '+' + s
}
```

- [ ] **Step 5: 写 `web/src/utils/countUp.ts`**

```ts
const last: Record<string, number> = {}

export function countUp(
  el: { textContent: string },
  target: number,
  key: string | null,
  fmt: (n: number) => string,
  dur = 400,
): void {
  if (key && last[key] === target) { el.textContent = fmt(target); return }
  if (key) last[key] = target
  const start = performance.now()
  const frame = (now: number) => {
    const t = Math.min(1, (now - start) / dur)
    const eased = 1 - Math.pow(1 - t, 3)
    el.textContent = fmt(target * eased)
    if (t < 1) requestAnimationFrame(frame)
  }
  requestAnimationFrame(frame)
}
```

- [ ] **Step 6: Run 验证通过**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/utils/__tests__/format.test.ts src/utils/__tests__/countUp.test.ts
```
Expected: `format.test.ts` 2 项通过；`countUp.test.ts` 2 项通过。

- [ ] **Step 7: Commit**

```bash
git add web/src/utils/format.ts web/src/utils/countUp.ts web/src/utils/__tests__/format.test.ts web/src/utils/__tests__/countUp.test.ts
git commit -m "feat(web): formatMoney/formatSigned + cubic-out countUp（带 key 防重放）"
```

---

### Task 3: 类型层 `src/types.ts`

**Files:**
- Create: `web/src/types.ts`
- Test: `web/src/types/__tests__/types.test.ts`（编译/结构校验）

**Interfaces:**
- Consumes: 无。
- Produces: 全部服务端 DTO 的 TS 接口，供 store/api/components 引用：
  `Bill`、`SubCategory`、`Category`、`Account`、`Budget`、`AuthResponse`、`MessageResponse`、`MeResponse`、`SyncResponse`、`MoneyStyle`（`'EXPENSE'|'INCOME'`）、`BillSource`（`'WEB'|'APP'|'QQ'`）。字段名（camelCase）见 Global Constraints 基线，逐字对齐。

- [ ] **Step 1: 写 `web/src/types.ts`**

```ts
export type MoneyStyle = 'EXPENSE' | 'INCOME'
export type BillSource = 'WEB' | 'APP' | 'QQ'
export type AccountType =
  | 'CASH' | 'BANK_CARD' | 'WECHAT' | 'ALIPAY' | 'VIRTUAL' | 'OTHER' | 'CREDIT' | 'LOAN'

export interface SubCategory { id: number; name: string; parentCategoryId: number }

export interface Category {
  id: number; name: string; iconName: string; type: MoneyStyle;
  subCategories: SubCategory[]; // wire `billType` maps to this `type` field via alias below
}

export interface Account {
  id: number; name: string; balance: number; iconColor: string;
  updatedAt: number; deleted: boolean;
  // 资产 Plan（worktree 未合并）：将来 server 会补下列可选字段，先预留
  type?: AccountType; isLiability?: boolean; openingBalance?: number;
}

export interface Bill {
  id: number; amount: number; billType: MoneyStyle;
  categoryId: number; categoryName: string; subCategoryName: string | null;
  accountId: number; remark: string | null; date: number; source: BillSource | string;
  createdAt: number; updatedAt: number | null; deleted: boolean;
}

export interface SyncResponse {
  bills: Bill[]; serverTime: number; hasMore: boolean;
  nextAfter: number | null; nextAfterId: number | null;
}

export interface Budget {
  id: number; monthStart: number; amount: number; createdAt: number;
  updatedAt: number | null; deleted: boolean;
}

export interface AuthResponse { userId: number; token: string }
export interface MessageResponse { message: string }

export interface MeResponse {
  id: number; phone: string | null; qqNumber: string | null;
  qqOpenid: string | null; createdAt: string | null; aiDisabled: boolean;
}
```

> 注：server `CategoryDTO` 的 wire 字段是 `billType`（非 `type`）。为让类型与 wire 逐字对齐，`Category.type` 实际应收 `billType`。Task 5 的 api 会做映射，或这里改用 `type` 需要在 api 层规整。**裁定**：`types.ts` 用 **`type`** 表示收支方向（App 语义），api 层把 wire `billType` 映射到 `type`。保持不变，避免贯穿组件层散落 `billType`。

- [ ] **Step 2: 写校验测试 `web/src/types/__tests__/types.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import type { Bill, SyncResponse, Account, Category, MeResponse, Budget } from '../../types'

describe('types', () => {
  it('Bill exposes sync-relevant fields (used by fetchAllBills)', () => {
    const b: Bill = {
      id: 1, amount: 19.9, billType: 'EXPENSE', categoryId: 1, categoryName: '三餐',
      subCategoryName: null, accountId: 1, remark: null, date: 1700000000000,
      source: 'WEB', createdAt: 1700000000000, updatedAt: 1700000000000, deleted: false,
    }
    expect(b.updatedAt).toBeTypeOf('number')
  })
  it('SyncResponse has composite cursor fields', () => {
    const r: SyncResponse = { bills: [], serverTime: 0, hasMore: false, nextAfter: null, nextAfterId: null }
    expect(r.nextAfterId).toBeNull()
  })
  it('Account has asset-plan optional fields', () => {
    const a: Account = { id: 1, name: '微信', balance: 0, iconColor: '#28C145', updatedAt: 0, deleted: false, type: 'WECHAT', isLiability: false }
    expect(a.isLiability).toBe(false)
  })
  // 其余为纯类型占位，引用即校验
  it('other interfaces are constructible', () => {
    const c: Category = { id: 1, name: '三餐', iconName: 'food', type: 'EXPENSE', subCategories: [] }
    const m: MeResponse = { id: 1, phone: null, qqNumber: null, qqOpenid: null, createdAt: null, aiDisabled: false }
    const bu: Budget = { id: 1, monthStart: 1700000000000, amount: 2000, createdAt: 0, updatedAt: null, deleted: false }
    expect(c.type).toBe('EXPENSE'); expect(m.aiDisabled).toBe(false); expect(bu.amount).toBe(2000)
  })
})
```

- [ ] **Step 3: Run 验证**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/types/__tests__/types.test.ts
```
Expected: 通过（纯结构校验）。若 `Category` 的 `type` 字段与 wire `billType` 冲突报错，按裁定在 api 层映射 —— 本任务不报错即通过。

- [ ] **Step 4: Commit**

```bash
git add web/src/types.ts web/src/types/__tests__/types.test.ts
git commit -m "feat(web): 服务端 DTO 类型层 types.ts（camelCase wire 基线）"
```

---

### Task 4: api 封装 `src/api/http.ts`

**Files:**
- Create: `web/src/api/http.ts`
- Test: `web/src/api/__tests__/http.test.ts`

**Interfaces:**
- Consumes: 无。
- Produces: `api<T>(path, opts?): Promise<T>` —— 自动带 JSON header + `Authorization: Bearer <token>`（token 取自 `localStorage.rkl_token`）；`r.status===401 && token` 时清 token + `window.location.href='/login'`；`r.status===409` 时抛 `HttpError`（`statusCode=409`）供条件 PUT 检测；其余非 2xx **不抛**、直接 `r.json()` 返回（沿用原 web `api()` 行为：以 `message` 字段由调用方呈现）。导出 `setToken/getToken/clearToken`。

- [ ] **Step 1: 写失败测试 `web/src/api/__tests__/http.test.ts`**

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, setToken, getToken, clearToken } from '../http'

function mockFetch(status: number, json: unknown) {
  return vi.fn().mockResolvedValue({ status, json: async () => json })
}

describe('api', () => {
  beforeEach(() => { localStorage.clear(); clearToken() })
  afterEach(() => { vi.restoreAllMocks() })

  it('sends Bearer token and returns json', async () => {
    setToken('abc')
    const f = mockFetch(200, { ok: true })
    globalThis.fetch = f as any
    const r = await api('/api/bills/sync')
    expect(f).toHaveBeenCalledWith('/api/bills/sync', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc' }),
    }))
    expect(r).toEqual({ ok: true })
  })

  it('clears token and redirects on 401 with a token', async () => {
    setToken('abc')
    const f = mockFetch(401, {})
    globalThis.fetch = f as any
    let redirected = ''
    const orig = window.location; Object.defineProperty(window, 'location', { value: { ...orig, href: '' }, configurable: true })
    Object.defineProperty(window.location, 'href', { set: (v: string) => { redirected = v }, get: () => '' })
    await api('/api/x')
    expect(localStorage.getItem('rkl_token')).toBeNull()
    expect(redirected).toBe('/login')
    Object.defineProperty(window, 'location', { value: orig, configurable: true })
  })

  it('preserves 409 as HttpError for conditional PUT', async () => {
    const f = mockFetch(409, { message: 'conflict' })
    globalThis.fetch = f as any
    await expect(api('/api/bills/1', { method: 'PUT' })).rejects.toMatchObject({ statusCode: 409 })
  })
})
```

- [ ] **Step 2: Run 验证失败**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/api/__tests__/http.test.ts
```
Expected: 模块不存在 → 失败（预期）。

- [ ] **Step 3: 写 `web/src/api/http.ts`**

```ts
export class HttpError extends Error {
  statusCode: number
  constructor(status: number, message?: string) {
    super(message ?? `HTTP ${status}`);
    this.statusCode = status
  }
}

const TOKEN_KEY = 'rkl_token'
export function getToken(): string | null { return localStorage.getItem(TOKEN_KEY) }
export function setToken(t: string): void { localStorage.setItem(TOKEN_KEY, t) }
export function clearToken(): void { localStorage.removeItem(TOKEN_KEY) }

interface ApiOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
  query?: Record<string, string | number | undefined>
}

export async function api<T = any>(path: string, opts: ApiOptions = {}): Promise<T> {
  const token = getToken()
  const h: Record<string, string> = { 'Content-Type': 'application/json', ...opts.headers }
  if (token) h['Authorization'] = 'Bearer ' + token

  let url = path
  if (opts.query) {
    const q = new URLSearchParams()
    Object.entries(opts.query).forEach(([k, v]) => { if (v !== undefined) q.set(k, String(v)) })
    const qs = q.toString()
    if (qs) url += (url.includes('?') ? '&' : '?') + qs
  }

  const r = await fetch(url, {
    method: opts.method ?? 'GET',
    headers: h,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  if (r.status === 401 && token) { clearToken(); window.location.href = '/login' }
  if (r.status === 409) throw new HttpError(409, '并发冲突')
  return r.json() as Promise<T>
}
```

- [ ] **Step 4: Run 验证通过**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/api/__tests__/http.test.ts
```
Expected: 3 项通过。

- [ ] **Step 5: Commit**

```bash
git add web/src/api/http.ts web/src/api/__tests__/http.test.ts
git commit -m "feat(web): api() 封装 —— Bearer token、401 登出、409 HttpError"
```

---

### Task 5: 业务 API 端点（auth / bills / accounts / budgets / categories）

**Files:**
- Create: `web/src/api/auth.ts`
- Create: `web/src/api/bills.ts`
- Create: `web/src/api/accounts.ts`
- Create: `web/src/api/budgets.ts`
- Create: `web/src/api/categories.ts`
- Test: `web/src/api/__tests__/bills.test.ts`

**Interfaces:**
- Consumes: Task 4 的 `api`；Task 3 的类型。
- Produces:
  - `auth.ts`: `login(phone,pwd)`、`register(phone,pwd)`、`qqLogin(code)`、`getMe()`、`changePassword(old,new)`、`setAiDisabled(disabled)`、`bindQq(qqNumber)`、`unbindQq()`。
  - `bills.ts`: `fetchAllBills()`（composite cursor 分页，复用原 web 逻辑）、`createBill(payload)`、`updateBill(id,payload)`、`deleteBill(id)`、`parseBill(text)`（NLU）。
  - `accounts.ts`: `getAccounts()`、`createAccount(name,iconColor,balance)`、`updateAccount(id,{name?,iconColor?,balance?})`。
  - `budgets.ts`: `listBudgets()`、`upsertBudget(monthStart,amount)`。
  - `categories.ts`: `getCategories()`。

- [ ] **Step 1: 写 `web/src/api/auth.ts`**

```ts
import { api } from './http'
import type { AuthResponse, MeResponse, MessageResponse } from '../types'

export const auth = {
  login: (phone: string, password: string) =>
    api<AuthResponse>('/api/auth/login', { method: 'POST', body: { phone, password } }),
  register: (phone: string, password: string) =>
    api<AuthResponse>('/api/auth/register', { method: 'POST', body: { phone, password } }),
  qqLogin: (code: string) =>
    api<AuthResponse>('/api/auth/qq-login', { method: 'POST', body: { code } }),
  me: () => api<MeResponse>('/api/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    api<MessageResponse>('/api/auth/password', { method: 'POST', body: { oldPassword, newPassword } }),
  setAiDisabled: (disabled: boolean) =>
    api<MessageResponse>('/api/auth/ai', { method: 'PUT', body: { disabled } }),
  bindQq: (qqNumber: string) =>
    api<MessageResponse>('/api/auth/bind-qq', { method: 'POST', body: { qqNumber } }),
  unbindQq: () => api<MessageResponse>('/api/auth/unbind-qq', { method: 'POST' }),
}
```

- [ ] **Step 2: 写 `web/src/api/bills.ts`**

```ts
import { api } from './http'
import type { Bill, SyncResponse, MoneyStyle } from '../types'

export interface CreateBillPayload {
  amount: number; billType: MoneyStyle; categoryId: number; categoryName: string;
  subCategoryName?: string | null; accountId: number; remark?: string | null; date?: number
}

/** Forward-paginate all bills using the server's composite cursor (updatedAt, id).
 *  Ported 1:1 from web/index.html fetchAllBills() — keep behavior identical. */
export async function fetchAllBills(): Promise<Bill[]> {
  const all: Bill[] = []
  let after = 0, afterId: number | null = null, hasMore = true, guard = 0
  while (hasMore && guard++ < 500) {
    const query: Record<string, number | undefined> = { limit: 200 }
    if (after > 0) query.after = after
    if (afterId != null) query.afterId = afterId
    const res = await api<SyncResponse>('/api/bills/sync', { query })
    const list = res.bills || []
    if (list.length) all.push(...list)
    after = res.nextAfter != null ? res.nextAfter : Math.max(0, ...list.map(b => b.updatedAt || 0))
    afterId = res.nextAfterId != null ? res.nextAfterId : null
    hasMore = !!res.hasMore && list.length === 200
  }
  return all
}

export const bills = {
  create: (payload: CreateBillPayload) =>
    api<Bill>('/api/bills', { method: 'POST', body: payload }),
  update: (id: number, payload: Partial<CreateBillPayload> & { baseUpdatedAt?: number }) =>
    api<Bill>(`/api/bills/${id}`, { method: 'PUT', body: payload }),
  remove: (id: number) =>
    api<{ message: string }>(`/api/bills/${id}`, { method: 'DELETE' }),
  parse: (text: string) =>
    api<{ message?: string; bill?: Bill } | { message: string }>('/api/bills/parse', { method: 'POST', body: { text } }),
}
```

- [ ] **Step 3: 写 `web/src/api/accounts.ts`**

```ts
import { api } from './http'
import type { Account, MessageResponse } from '../types'

export const accounts = {
  list: () => api<Account[]>('/api/accounts'),
  create: (name: string, iconColor: string, balance = 0) =>
    api<Account>('/api/accounts', { method: 'POST', body: { name, iconColor, balance } }),
  update: (id: number, p: { name?: string; iconColor?: string; balance?: number } | undefined) =>
    api<Account>(`/api/accounts/${id}`, { method: 'PUT', body: p ?? {} }),
  remove: (id: number) =>
    api<MessageResponse>(`/api/accounts/${id}`, { method: 'DELETE' }),
}
```

- [ ] **Step 4: 写 `web/src/api/budgets.ts`**

```ts
import { api } from './http'
import type { Budget } from '../types'

export const budgets = {
  list: () => api<Budget[]>('/api/budgets'),
  upsert: (monthStart: number, amount: number) =>
    api<Budget>('/api/budgets', { method: 'PUT', body: { monthStart, amount } }),
}
```

- [ ] **Step 5: 写 `web/src/api/categories.ts`**

```ts
import { api } from './http'
import type { Category } from '../types'

// server CategoryDTO wire 用 `billType` 表收支方向；此处映射为 `type`（App 语义），贯穿组件用 `type`。
export function normalizeCategory(c: Category & { billType?: string }): Category {
  return { ...c, type: (c.type ?? (c as any).billType as never) }
}

export const categories = {
  list: async (): Promise<Category[]> =>
    (await api<Array<Category & { billType: string }>>('/api/bills/categories')).map(normalizeCategory),
}
```

- [ ] **Step 6: 写 `web/src/api/__tests__/bills.test.ts`**

```ts
import { describe, it, expect, vi } from 'vitest'
import { fetchAllBills } from '../bills'

function page(bills: any[], hasMore: boolean, nextAfter?: number, nextAfterId?: number) {
  return { bills, serverTime: 0, hasMore, nextAfter: nextAfter ?? null, nextAfterId: nextAfterId ?? null }
}

describe('fetchAllBills', () => {
  it('paginates through composite cursor until hasMore is false', async () => {
    const pages = [
      page([{ id: 1, updatedAt: 10 }], true, 10, null),
      page([{ id: 2, updatedAt: 20 }], false, 20, null),
    ]
    const calls: string[] = []
    globalThis.fetch = vi.fn(async (url: string) => {
      calls.push(url)
      const p = pages.shift()!
      return { status: 200, json: async () => p }
    }) as any
    const all = await fetchAllBills()
    expect(all.length).toBe(2)
    expect(calls[1]).toContain('after=10')
  })
})
```

- [ ] **Step 7: Run 验证**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/api/__tests__/bills.test.ts
```
Expected: 通过。

- [ ] **Step 8: Commit**

```bash
git add web/src/api/auth.ts web/src/api/bills.ts web/src/api/accounts.ts web/src/api/budgets.ts web/src/api/categories.ts web/src/api/__tests__/bills.test.ts
git commit -m "feat(web): 业务 API 端点（auth/bills/accounts/budgets/categories）+ fetchAllBills 分页测试"
```

---

### Task 6: Pinia stores —— auth + theme

**Files:**
- Create: `web/src/stores/auth.ts`
- Create: `web/src/stores/theme.ts`
- Test: `web/src/stores/__tests__/auth.test.ts`

**Interfaces:**
- Consumes: Task 4 `setToken/getToken/clearToken`、Task 5 `auth`/`fetchAllBills`；Task 3 类型。
- Produces:
  - `useAuthStore`: `{ token, user, ready, login(), loginByQq(), logout(), refresh(), hydrate() }`；`hydrate()` 从 localStorage 读 token 并调 `me()` 填充 user。
  - `useThemeStore`: `{ theme, toggle(), setLight(), setDark(), setSystem() }`；写 localStorage `rkl_theme`，并设置 `document.documentElement.dataset.theme`（`system` → 删除属性）。

- [ ] **Step 1: 写失败测试 `web/src/stores/__tests__/auth.test.ts`**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'

beforeEach(() => setActivePinia(createPinia()))

describe('auth store', () => {
  it('login stores token and marks ready', async () => {
    const s = useAuthStore()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ status: 200, json: async () => ({ token: 't', userId: 1 }) })
    await s.login('123', 'pwd')
    expect(s.token).toBe('t')
    expect(localStorage.getItem('rkl_token')).toBe('t')
  })
  it('logout clears token and user', async () => {
    const s = useAuthStore(); s.token = 't'; s.user = { id: 1 }
    s.logout()
    expect(s.token).toBeNull(); expect(s.user).toBeNull(); expect(localStorage.getItem('rkl_token')).toBeNull()
  })
})
```

- [ ] **Step 2: 写 `web/src/stores/auth.ts`**

```ts
import { defineStore } from 'pinia'
import { auth } from '../api/auth'
import { setToken, getToken, clearToken } from '../api/http'
import type { MeResponse } from '../types'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: getToken() as string | null, user: null as MeResponse | null, ready: false }),
  actions: {
    async login(phone: string, password: string) {
      const r = await auth.login(phone, password)
      this.token = r.token; setToken(r.token); this.ready = true
      await this.refresh()
    },
    async loginByQq(code: string) {
      const r = await auth.qqLogin(code)
      this.token = r.token; setToken(r.token); this.ready = true
      await this.refresh()
    },
    async refresh() { if (this.token) this.user = await auth.me() },
    logout() { this.token = null; this.user = null; this.ready = false; clearToken() },
    async hydrate() { if (this.token) { this.ready = true; try { await this.refresh() } catch { this.logout() } } },
  },
})
```

- [ ] **Step 3: 写 `web/src/stores/theme.ts`**

```ts
import { defineStore } from 'pinia'

const KEY = 'rkl_theme'
export type ThemeMode = 'system' | 'light' | 'dark'

function apply(mode: ThemeMode) {
  const el = document.documentElement
  if (mode === 'light') el.dataset.theme = 'light'
  else if (mode === 'dark') el.dataset.theme = 'dark'
  else delete el.dataset.theme
}

export const useThemeStore = defineStore('theme', {
  state: () => ({ theme: (localStorage.getItem(KEY) as ThemeMode) || 'system' }),
  actions: {
    set(mode: ThemeMode) { this.theme = mode; localStorage.setItem(KEY, mode); apply(mode) },
    toggle() { this.set(this.theme === 'dark' ? 'light' : 'dark') },
    init() { apply(this.theme) },
  },
})
```

- [ ] **Step 4: 写 `web/src/stores/__tests__/theme.test.ts`**（追加到 auth 测试同目录可选）——本任务验收以 auth 测试通过 + `vue-tsc` 无错为准；theme 为纯 DOM 逻辑，以 root 手动验证（Task 9）。

- [ ] **Step 5: Run 验证**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/stores/__tests__/auth.test.ts
```
Expected: 2 项通过。

- [ ] **Step 6: Commit**

```bash
git add web/src/stores/auth.ts web/src/stores/theme.ts web/src/stores/__tests__/auth.test.ts
git commit -m "feat(web): Pinia auth store + theme store（rkl_theme / data-theme 应用）"
```

---

### Task 7: 路由 + 守卫 + 全局主题初始化

**Files:**
- Modify: `web/src/router/index.ts`（替换 Task 1 占位）
- Create: `web/src/views/Login.vue`（Task 8 落成，本任务先解析，守卫需 import）
- Create: `web/src/router/guards.ts`
- Test: `web/src/router/__tests__/guards.test.ts`

**Interfaces:**
- Consumes: Task 6 `useAuthStore`.
- Produces: `routes` with `/`(landing)、`/login`、`/console`；`beforeEach` 守卫：访问 `/console` 若未 `ready`/无 token → redirect `/login`；访问 `/login` 若已登录 → redirect `/console`。导出 `consoleGuard`/`loginGuard` 供测试。

- [ ] **Step 1: 写 `web/src/router/guards.ts`**

```ts
import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'

export const authGuard = async (to: any) => {
  const store = useAuthStore()
  if (!store.ready) await store.hydrate()
  if (to.path === '/console' && !store.token) return '/login'
  if (to.path === '/login' && store.token) return '/console'
  return true
}

export function installGuards(router: Router) { router.beforeEach(authGuard) }
```

- [ ] **Step 2: 写 `web/src/router/index.ts`（替换）**

```ts
import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/Landing.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/console', name: 'console', component: () => import('../views/console/ConsoleLayout.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })
installGuards(router)
export default router
```

- [ ] **Step 3: 写守卫测试 `web/src/router/__tests__/guards.test.ts`**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import { authGuard } from '../guards'

beforeEach(() => setActivePinia(createPinia()))

describe('authGuard', () => {
  it('redirects unauthenticated /console to /login', async () => {
    const s = useAuthStore(); s.token = null; s.ready = true
    expect(await authGuard({ path: '/console' })).toBe('/login')
  })
  it('redirects authenticated /login to /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/login' })).toBe('/console')
  })
  it('allows authenticated /console', async () => {
    const s = useAuthStore(); s.token = 't'; s.ready = true
    expect(await authGuard({ path: '/console' })).toBe(true)
  })
})
```

- [ ] **Step 4: Run 验证**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/router/__tests__/guards.test.ts
```
Expected: 3 项通过。此时 `views/Landing.vue`、`views/Login.vue`、`views/console/ConsoleLayout.vue` 尚未创建，`vue-tsc` 会报缺文件 —— 暂时把三个 `() => import(...)` 用 `// @ts-ignore` 或先创建空组件。**本任务先放三个空组件文件（Task 8/9 填实）**，注释标注。

- [ ] **Step 5: 建三个占位组件**

- `web/src/views/Landing.vue`：`<template><div class="page">落地页（Plan B 实现）</div></template>`
- `web/src/views/Login.vue`：`<template><div class="page">登录（Task 8 实现）</div></template>`
- `web/src/views/console/ConsoleLayout.vue`：`<template><div class="page">控制台（Task 9 实现）</div></template>`

- [ ] **Step 6: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误。

- [ ] **Step 7: Commit**

```bash
git add web/src/router/index.ts web/src/router/guards.ts web/src/router/__tests__/guards.test.ts web/src/views/Landing.vue web/src/views/Login.vue web/src/views/console/ConsoleLayout.vue
git commit -m "feat(web): 路由守卫 console->login 重定向 + 三个视图占位"
```

---

### Task 8: 登录页 `views/Login.vue` + 全局 toast

**Files:**
- Create: `web/src/views/Login.vue`
- Create: `web/src/composables/useToast.ts`
- Create: `web/src/components/GlobalToast.vue`
- Modify: `web/src/App.vue`（挂 `<GlobalToast/>`）
- Test: 无（以手动 + typecheck 验收；登录走 `authStore.login` 已有单测覆盖逻辑）

**Interfaces:**
- Consumes: Task 6 `useAuthStore`、Task 5 `auth`。
- Produces: `useToast()`: `{ toasts: Toast[], push(msg, kind?, dur?), close(i) }`；`GlobalToast` 静态渲染，支持 `ok`/`err` 色，替代原 web 的 `S.msg`。

- [ ] **Step 1: 写 `web/src/composables/useToast.ts`**

```ts
import { reactive, readonly } from 'vue'

export interface Toast { id: number; msg: string; kind: 'ok' | 'err' }
const state = reactive<{ toasts: Toast[] }>({ toasts: [] })
let seq = 0

export function useToast() {
  function push(msg: string, kind: 'ok' | 'err' = 'ok', dur = 2200) {
    const id = ++seq
    state.toasts.push({ id, msg, kind })
    setTimeout(() => close(id), dur)
  }
  function close(id: number) { const i = state.toasts.findIndex(t => t.id === id); if (i >= 0) state.toasts.splice(i, 1) }
  return { toasts: readonly(state.toasts), push, close }
}
```

- [ ] **Step 2: 写 `web/src/components/GlobalToast.vue`**

```vue
<script setup lang="ts">
import { useToast } from '../composables/useToast'
const { toasts, close } = useToast()
</script>

<template>
  <div class="toast-wrap" aria-live="polite">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind" @click="close(t.id)">
        {{ t.msg }}
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-wrap { position: fixed; top: 16px; left: 50%; transform: translateX(-50%); z-index: 999; display: flex; flex-direction: column; gap: 8px; align-items: center; }
.toast { padding: 10px 18px; border-radius: 10px; background: var(--card); color: var(--text); box-shadow: var(--shadow); cursor: pointer; font-size: 14px; }
.toast.ok { border-left: 3px solid var(--income, #04A433); }
.toast.err { border-left: 3px solid var(--expense, #CA3032); }
.toast-enter-active, .toast-leave-active { transition: all .2s var(--ease, ease); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
```

- [ ] **Step 3: 写 `web/src/views/Login.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/useToast'
import { useThemeStore } from '../stores/theme'

const router = useRouter()
const store = useAuthStore()
const theme = useThemeStore()
const toast = useToast()

const mode = ref<'login' | 'register'>('login')
const phone = ref('')
const pwd = ref('')
const qqCode = ref('')
const busy = ref(false)

async function submit() {
  if (!phone.value || !pwd.value) { toast.push('手机号或密码不能为空', 'err'); return }
  if (mode.value === 'register' && pwd.value.length < 6) { toast.push('密码长度至少6位', 'err'); return }
  busy.value = true
  try {
    if (mode.value === 'login') await store.login(phone.value, pwd.value)
    else await store.login(phone.value, pwd.value) // register 返回同结构 token
    toast.push('登录成功')
    router.replace('/console')
  } catch (e: any) {
    toast.push(e?.message || '登录失败', 'err')
  } finally { busy.value = false }
}

async function qqLogin() {
  if (!qqCode.value) { toast.push('请输入 QQ 登录码', 'err'); return }
  busy.value = true
  try { await store.loginByQq(qqCode.value); toast.push('QQ登录成功'); router.replace('/console') }
  catch (e: any) { toast.push(e?.message || 'QQ 登录失败', 'err') }
  finally { busy.value = false }
}
function toggleMode() { mode.value = mode.value === 'login' ? 'register' : 'login' }
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1>RinklNote</h1>
      <p class="sub">记账 · 资产 · AI 洞察</p>
      <div class="toggle">
        <button :class="mode === 'login' ? 'on exp' : ''" @click="mode = 'login'">登录</button>
        <button :class="mode === 'register' ? 'on exp' : ''" @click="mode = 'register'">注册</button>
      </div>
      <input v-model="phone" type="text" placeholder="手机号" />
      <input v-model="pwd" type="password" placeholder="密码" @keyup.enter="submit" />
      <button class="btn btn-primary" :disabled="busy" @click="submit">{{ mode === 'login' ? '登录' : '注册' }}</button>
      <hr />
      <input v-model="qqCode" type="text" placeholder="QQ 登录码（向机器人发「登录」获取）" />
      <button class="btn" :disabled="busy" @click="qqLogin">QQ 登录</button>
      <button class="theme-btn" @click="theme.toggle()">{{ theme.theme === 'dark' ? '🌙 暗色' : '☀️ 亮色' }}</button>
    </div>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg, #F7F7F9); padding: 16px; }
.login-card { width: 100%; max-width: 360px; background: var(--card, #FFF); border-radius: 18px; padding: 28px 24px; box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.1)); display: flex; flex-direction: column; gap: 12px; }
.login-card h1 { font-size: 28px; margin: 0; }
.login-card .sub { color: var(--muted, #888); margin: 0 0 8px; font-size: 13px; }
.toggle { display: flex; gap: 8px; }
.toggle button { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border, #EEE); background: none; color: var(--muted, #888); font-size: 14px; cursor: pointer; }
.toggle button.on.exp { background: var(--expense, #CA3032); color: #fff; border-color: transparent; font-weight: 600; }
input { padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border, #EEE); font-size: 16px; background: var(--card, #FFF); color: var(--text, #111); }
.btn { padding: 12px 18px; border-radius: 12px; border: none; font-size: 15px; cursor: pointer; background: var(--card, #FFF); color: var(--text, #111); border: 1px solid var(--border, #EEE); }
.btn btn-primary, .btn-primary { background: var(--primary, #7EC1FC); color: #0b2b44; font-weight: 600; }
hr { border: none; border-top: 1px solid var(--border-light, #EEE); margin: 8px 0; }
.theme-btn { align-self: flex-start; background: none; border: none; color: var(--muted, #888); cursor: pointer; font-size: 13px; }
</style>
```

> 注：注册与登录走同一 `authStore.login`，因 server 注册/登录返回同结构 `{token}`。若注册服务端返回无 token 需区分，见 AuthRoutes —— 当前 `register` 返回 `AuthResponse{userId,token}`，一致。

- [ ] **Step 4: 修改 `web/src/App.vue`**

```vue
<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalToast from './components/GlobalToast.vue'
</script>

<template>
  <RouterView />
  <GlobalToast />
</template>
```

- [ ] **Step 5: Run typecheck + dev 手动验证**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误。

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run dev
```
手动：打开 `http://localhost:5173/console` 应被守卫重定向到 `/login`，登录页渲染正常，切换主题生效。

- [ ] **Step 6: Commit**

```bash
git add web/src/views/Login.vue web/src/composables/useToast.ts web/src/components/GlobalToast.vue web/src/App.vue
git commit -m "feat(web): 登录页（手机/QQ）+ 全局 toast + 主题切换"
```

---

### Task 9: 控制台骨架 ConsoleLayout + 六 tab 路由

**Files:**
- Create: `web/src/views/console/ConsoleLayout.vue`
- Create: `web/src/views/console/Bookkeeping.vue`
- Create: `web/src/views/console/Bills.vue`
- Create: `web/src/views/console/Charts.vue`
- Create: `web/src/views/console/Assets.vue`
- Create: `web/src/views/console/Me.vue`
- Create: `web/src/views/console/Settings.vue`
- Modify: `web/src/router/index.ts`（`/console` 挂 children 六 tab）
- Test: 无（手动验收布局 + 路由切换）

**Interfaces:**
- Consumes: Task 8 toast、Task 6 auth store、Task 5 api。
- Produces: `/console` 壳（侧边 + 移动底部六 tab 导航），各 tab 为空提示（Plan C 填实）。`ConsoleLayout` 从 `useAuthStore` 读 token，若为空触发路由守卫跳到 `/login`（已由 guards 处理）。

- [ ] **Step 1: 写 `web/src/router/index.ts`（加 children）**

```ts
import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'

const consoleChildren = [
  { path: '', name: 'bookkeeping', component: () => import('../views/console/Bookkeeping.vue') },
  { path: 'bills', name: 'bills', component: () => import('../views/console/Bills.vue') },
  { path: 'charts', name: 'charts', component: () => import('../views/console/Charts.vue') },
  { path: 'assets', name: 'assets', component: () => import('../views/console/Assets.vue') },
  { path: 'me', name: 'me', component: () => import('../views/console/Me.vue') },
  { path: 'settings', name: 'settings', component: () => import('../views/console/Settings.vue') },
]

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/Landing.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/console', name: 'console', component: () => import('../views/console/ConsoleLayout.vue'), children: consoleChildren },
]

const router = createRouter({ history: createWebHistory(), routes })
installGuards(router)
export default router
```

- [ ] **Step 2: 写 `web/src/views/console/ConsoleLayout.vue`**

```vue
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
```

- [ ] **Step 3: 写 `web/src/views/console/console.css`**

```css
.console { display: flex; min-height: 100vh; background: var(--bg, #F7F7F9); }
.sidebar { width: 200px; padding: 20px 12px; display: flex; flex-direction: column; gap: 8px; border-right: 1px solid var(--border-light, #EEE); }
.sidebar h1 { font-size: 20px; margin: 0 0 16px; }
.sidebar nav { display: flex; flex-direction: column; gap: 4px; }
.sidebar nav button, .logout { text-align: left; padding: 10px 12px; border-radius: 10px; border: none; background: none; color: var(--muted, #888); cursor: pointer; font-size: 14px; }
.sidebar nav button.active { background: var(--primary-soft, rgba(126,193,252,.14)); color: var(--primary, #7EC1FC); font-weight: 600; }
.logout { margin-top: auto; color: var(--expense, #CA3032); }
.content { flex: 1; padding: 20px 24px; overflow-y: auto; }
.mobile-nav { display: none; }

@media (max-width: 768px) {
  .sidebar { display: none; }
  .mobile-nav { display: flex; position: fixed; left: 0; right: 0; bottom: 0; height: 60px; background: var(--card, #FFF); border-top: 1px solid var(--border-light, #EEE); }
  .mobile-nav button { flex: 1; border: none; background: none; color: var(--muted, #888); font-size: 11px; }
  .mobile-nav button.active { color: var(--primary, #7EC1FC); font-weight: 600; }
  .content { padding: 16px 14px 84px; }
}
```

- [ ] **Step 4: 建六个占位 tab 组件**

每个 `<template><div class="page"><h2>记账</h2><p>Plan C 实现</p></div></template>`（按 tab 名替换标题：记账/账单/图表/资产/我的/设置）。

- [ ] **Step 5: Run typecheck + dev**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误。
Run dev，登录后进入 `/console`，桌面显示侧边六 tab、移动显示底部五 tab + 设置从我的页进入。切换路由正常。

- [ ] **Step 6: Commit**

```bash
git add web/src/router/index.ts web/src/views/console/ConsoleLayout.vue web/src/views/console/console.css web/src/views/console/Bookkeeping.vue web/src/views/console/Bills.vue web/src/views/console/Charts.vue web/src/views/console/Assets.vue web/src/views/console/Me.vue web/src/views/console/Settings.vue
git commit -m "feat(web): 控制台骨架，六 tab 路由，侧边/移动双导航"
```

---

### Task 10: 终局 build 冒烟 + 文档

**Files:**
- Modify: `web/README.md`（新增，工程说明/命令）
- Modify: 根 `docs/` 或 `CLAUDE.md`（提及 web 为新 Vite 工程，可选）

**Interfaces:** Consumes: 全部先前任务。

- [ ] **Step 1: 写 `web/README.md`**

```md
# RinklNote Web 控制台

Vite + Vue 3 + TypeScript + Pinia + Vue Router + ECharts。

```bash
npm install
npm run dev        # http://localhost:5173，/api 代理到 Ktor :8080
npm run typecheck  # vue-tsc --noEmit
npm test           # vitest run
npm run build      # vue-tsc --noEmit && vite build → web/dist
```
```

- [ ] **Step 2: Run 终局 build**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run build
```
Expected: `vue-tsc --noEmit` 通过，`vite build` 产出 `web/dist/index.html` + `assets/*`。

- [ ] **Step 3: Commit**

```bash
git add web/README.md
git commit -m "docs(web): 工程说明与命令（dev/typecheck/test/build）"
```

---

## Self-Review

**Spec 覆盖（本 Plan A 对应 spec 哪些）**：
- 架构/路由：Task 1(脚手架) + Task 7(路由守卫)。✅
- 登录闭环：Task 8(Login.vue) + Task 5(auth api) + Task 6(auth store)。✅
- 核心逻辑复用（api/fetchAllBills/countUp/主题）：Task 2(countUp/format)、Task 4(api)、Task 5(fetchAllBills)、Task 6(theme)。✅
- 数据源隔离：本 plan 只建控制台 api 层；落地页在 Plan B（不会 import 控制台 store）。✅
- 类型层 + vue-tsc 校验：Task 3(types.ts) + 每 task 的 typecheck。✅
- 控制台骨架（六 tab 路由）：Task 9。✅
- 落地页内容页 / 控制台六 tab 实内容 / ECharts 图表：**属于 Plan B / Plan C，本 plan 不含**，非缺口。

**Placeholder 扫描**：无 TBD；所有步都有实际代码/命令/期望。占位组件用明确 "Plan B/C 实现" 标注。

**Type 一致性**：`fetchAllBills` 在 Task 5 定义、Task 5 测试引用；`api` 在 Task 4、Task 5/6 复用之；`useAuthStore.login` 在 Task 6 定义、Task 8 调用；`useToast.push` Task 8 定义即用。`Category.type` 映射在 Task 5 `normalizeCategory` 实现并在 Task 5 测试覆盖。命名一致。

> **执行注意**：`npm install` 在 Task 1 首次跑，若 npm registry 也被网络阻断会失败（本机仅确认 github.com 被阻断，registry.npmjs.org 未测）。Task 1 Step 11 已注明先 curl 探测。若 registry 不可达，需用户提供可用 npm 镜像或换网络。
