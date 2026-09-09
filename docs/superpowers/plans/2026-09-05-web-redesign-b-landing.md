# Web 重做 · Plan B：公开落地页（Mock 数据展示）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `views/Landing.vue` 占位替换成一整页面向公开访客的 App 能力演示落地页（主题 CSS 落地、Mock 数据源、Hero + 记账/资产/AI 三大能力区 + 进入控制台 CTA），数据全部来自 `src/mock/demo-data.ts`，不触后端/不依赖登录。

**Architecture:** 单页 `/` 单向滚动长页。用已有的 `countUp`/主题 store/语义色；新增全局主题 CSS（`data-theme` + `prefers-color-scheme` 双选择器）、`v-reveal` 滚动渐入 directive、`src/mock/demo-data.ts` 静态 Mock。落地页组件只 import `mock/demo-data.ts` + `utils/countUp`，**严禁 import 控制台 api/store**（数据源隔离）。头部「进入控制台」按钮 → `/login`。

**Tech Stack:** Vite + Vue 3 `<script setup lang="ts">` + TypeScript + Pinia + Vue Router + Vue 自定义 directive（IntersectionObserver 滚动揭示）。

## Global Constraints

以下为 spec/Plan A 全项目约束，本计划每个任务隐含继承：

- **栈**：Vite + Vue 3 `<script setup lang="ts">` + TypeScript + Pinia + Vue Router。`vue-tsc --noEmit` 纳入构建前校验。
- **数据源隔离（强约束）**：落地页只 import `src/mock/demo-data.ts`（Mock）；**不得** import `src/api/**`、`src/stores/**`（控制台 store）。落地页用 `countUp`（`src/utils/countUp.ts`）作金额动效是被允许的共享工具。
- **主题**：`data-theme` 取 `light`/`dark`，为空则用 `prefers-color-scheme`；localStorage key `rkl_theme`（`system`/`light`/`dark`）。语义色沿用 App：`--expense:#CA3032`、`--income:#04A433`、`--primary:#7EC1FC`、暗黑 `--bg:#1A1A1E`/`--card:#252528`。主题 store 导出 `set/toggle/init`（代码为权威）。
- **字体**：`'Inter','Noto Sans SC',system-ui,-apple-system,sans-serif`（App 栈）。金额用 `tabular-nums`（`font-variant-numeric:tabular-nums` + `font-feature-settings:"tnum"`）。
- **强视觉方向（spec §落地页）**：大字号显示标题、渐变/噪点背景氛围、分区错落布局、滚动渐入动画。**不做**通用 AI 风：不用紫色渐变主打、不用 Space Grotesk 替代字体、不用 cookie-cutter 卡片模板。
- **commit 卫生**：精确路径 `git add web/...` 禁 `add -A`；跳 `server/build`、`.idea`、`.claude`、`node_modules`、`web/dist`。
- **不含**：本 plan 不实现控制台六 tab、不定义控制台专属 CSS（属 Plan C）。落地页仅新增公共主题 CSS + 落地页组件。

---

### Task 1 (B1): 全局主题 CSS + main.ts 接线 + 滚动揭示 directive

**Files:**
- Create: `web/src/styles/theme.css`
- Create: `web/src/directives/reveal.ts`
- Modify: `web/src/main.ts`（import theme.css + 注册 reveal directive）
- Modify: `web/src/App.vue`（setup 里调 `useThemeStore().init()`）

**Interfaces:**
- Consumes: 无。
- Produces: 全局设计 token CSS 变量（`--primary`/`--expense`/`--income`/`--bg`/`--card`/`--text`/`--muted`/`--border`/`--border-light`/`--radius`/`--radius-sm`/`--primary-soft`/`--tag-*-bg`/`--tag-*-fg`/`--shadow-sm`/`--shadow`/`--ease`/`--dur-fast`/`--dur-med`/`--dur-slow`）+ 基础 reset/字体/`.amount` + 暗黑双选择器（media + data-theme）。`reveal` directive 挂载时加 `.reveal` 类，IntersectionObserver 交叉后加 `.revealed`。`App.vue` 挂载时 `theme.init()` 应用 `data-theme`（补齐 Plan A 遗留的 init 无调用点）。

- [ ] **Step 1: 写 `web/src/styles/theme.css`**

```css
:root {
  --primary:#7EC1FC; --expense:#CA3032; --income:#04A433;
  --bg:#F7F7F9; --card:#FFFFFF; --text:#1f2937; --muted:#6b7280;
  --border:#e5e7eb; --border-light:#f3f4f6;
  --radius:15px; --radius-sm:10px;
  --primary-soft:#eff6ff;
  --tag-web-bg:#dcfce7; --tag-web-fg:#16a34a;
  --tag-qq-bg:#dbeafe;  --tag-qq-fg:#2563eb;
  --tag-app-bg:#f3f4f6; --tag-app-fg:#6b7280;
  --shadow-sm:0 1px 3px rgba(0,0,0,.04); --shadow:0 1px 8px rgba(0,0,0,.06);
  --ease:cubic-bezier(.4,0,.2,1);
  --dur-fast:120ms; --dur-med:220ms; --dur-slow:320ms;
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg:#1A1A1E; --card:#252528; --text:#EAEAEA; --muted:#AAAAAA;
    --border:#333333; --border-light:#2D2D30;
    --primary-soft:rgba(126,193,252,.14);
    --tag-web-bg:#0f2e1c; --tag-web-fg:#4ade80;
    --tag-qq-bg:#12294a;  --tag-qq-fg:#60a5fa;
    --tag-app-bg:#2D2D30; --tag-app-fg:#AAAAAA;
    --shadow-sm:0 1px 3px rgba(0,0,0,.4); --shadow:0 1px 8px rgba(0,0,0,.45);
  }
}
:root[data-theme="dark"] {
  --bg:#1A1A1E; --card:#252528; --text:#EAEAEA; --muted:#AAAAAA;
  --border:#333333; --border-light:#2D2D30;
  --primary-soft:rgba(126,193,252,.14);
  --tag-web-bg:#0f2e1c; --tag-web-fg:#4ade80;
  --tag-qq-bg:#12294a;  --tag-qq-fg:#60a5fa;
  --tag-app-bg:#2D2D30; --tag-app-fg:#AAAAAA;
  --shadow-sm:0 1px 3px rgba(0,0,0,.4); --shadow:0 1px 8px rgba(0,0,0,.45);
}

*, *::before, *::after { box-sizing:border-box; }
html, body, #app { margin:0; height:100%; }
body {
  font-family:'Inter','Noto Sans SC',system-ui,-apple-system,sans-serif;
  background:var(--bg); color:var(--text);
  -webkit-font-smoothing:antialiased; -webkit-text-size-adjust:100%;
}
a { color:inherit; text-decoration:none; }
button { font-family:inherit; }

.amount { font-variant-numeric:tabular-nums; font-feature-settings:"tnum"; }

/* Scroll reveal: .reveal → .revealed */
.reveal { opacity:0; transform:translateY(24px); transition:opacity .6s var(--ease), transform .6s var(--ease); }
.reveal.revealed { opacity:1; transform:none; }
@media (prefers-reduced-motion: reduce) {
  .reveal { opacity:1; transform:none; transition:none; }
  * { animation:none !important; transition:none !important; }
}
```

- [ ] **Step 2: 写 `web/src/directives/reveal.ts`**

```ts
import type { Directive } from 'vue'

export const reveal: Directive<HTMLElement> = {
  mounted(el) {
    el.classList.add('reveal')
    if (typeof IntersectionObserver === 'undefined') { el.classList.add('revealed'); return }
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting) { el.classList.add('revealed'); io.disconnect() }
      })
    }, { threshold: 0.12 })
    io.observe(el)
  },
}
```

- [ ] **Step 3: 修改 `web/src/main.ts`**

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { reveal } from './directives/reveal'
import './styles/theme.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('reveal', reveal)
app.mount('#app')
```

- [ ] **Step 4: 修改 `web/src/App.vue`**

```vue
<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalToast from './components/GlobalToast.vue'
import { useThemeStore } from './stores/theme'

// Plan A 遗留：theme.init() 无调用点。此处补齐 —— 挂载时应用 data-theme（data-theme CSS 变量本任务才定义）。
useThemeStore().init()
</script>

<template>
  <RouterView />
  <GlobalToast />
</template>
```

- [ ] **Step 5: Run typecheck（应无错，等 Task 2 的 mock 落地再做完整验证）**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: `vue-tsc --noEmit` 通过（新增 ts/css/directive 无类型错误）。

- [ ] **Step 6: Commit**

```bash
git add web/src/styles/theme.css web/src/directives/reveal.ts web/src/main.ts web/src/App.vue
git commit -m "feat(web): 全局主题 CSS + data-theme 接线 + 滚动渐入 reveal directive"
```

---

### Task 2 (B2): Mock 数据层 `src/mock/demo-data.ts` + 结构校验测试

**Files:**
- Create: `web/src/mock/demo-data.ts`
- Test: `web/src/mock/__tests__/demo-data.test.ts`

**Interfaces:**
- Consumes: 无。
- Produces: 供落地页组件消费的结构化静态数据：`DemoKind`（`'EXPENSE'|'INCOME'`）、`DemoBill`、`DemoDay`、`demoDays`、`DemoAccountKind`（`'asset'|'liability'`）、`DemoAccount`、`demoAccounts`、`DemoBudgetCategory`、`DemoBudget`、`demoBudget`、`DemoInsight`、`demoInsight`。

- [ ] **Step 1: 写结构校验测试 `web/src/mock/__tests__/demo-data.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { demoDays, demoAccounts, demoBudget, demoInsight } from '../demo-data'

describe('demo-data', () => {
  it('demoDays groups bills with a total and a label', () => {
    expect(demoDays.length).toBeGreaterThan(0)
    for (const d of demoDays) {
      expect(typeof d.date).toBe('string')
      expect(typeof d.label).toBe('string')
      expect(d.bills.length).toBeGreaterThan(0)
      // total = sum of |amount|
      expect(d.total).toBeCloseTo(d.bills.reduce((s, b) => s + b.amount, 0))
    }
  })
  it('demoAccounts has both asset and liability kinds', () => {
    expect(demoAccounts.some((a) => a.kind === 'asset')).toBe(true)
    expect(demoAccounts.some((a) => a.kind === 'liability')).toBe(true)
    for (const a of demoAccounts) expect(a.balance).toBeGreaterThanOrEqual(0)
  })
  it('demoBudget spent does not exceed total', () => {
    expect(demoBudget.spent).toBeLessThanOrEqual(demoBudget.total)
    expect(demoBudget.byCategory.map((c) => typeof c.amount).every((t) => t === 'number')).toBe(true)
  })
  it('demoInsight has a summary and highlights', () => {
    expect(demoInsight.summary.length).toBeGreaterThan(0)
    expect(demoInsight.highlights.length).toBeGreaterThan(0)
  })
})
```

- [ ] **Step 2: 写 `web/src/mock/demo-data.ts`**

```ts
export type DemoKind = 'EXPENSE' | 'INCOME'

export interface DemoBill {
  id: number
  amount: number
  category: string
  subCategory?: string
  kind: DemoKind
  time: string
  remark?: string
}

export interface DemoDay {
  date: string   // epoch ms string
  label: string  // '今天' | '昨天' | '8月4日'
  total: number  // 当日支出合计（正向数字）
  bills: DemoBill[]
}

export const demoDays: DemoDay[] = [
  {
    date: '2026-08-05', label: '今天', total: 68.5,
    bills: [
      { id: 1, amount: 25, category: '三餐', kind: 'EXPENSE', time: '08:24', remark: '早餐 · 豆浆油条' },
      { id: 2, amount: 18.5, category: '交通', kind: 'EXPENSE', time: '09:10', remark: '地铁通勤' },
      { id: 3, amount: 25, category: '三餐', kind: 'EXPENSE', time: '12:40', remark: '午餐' },
    ],
  },
  {
    date: '2026-08-04', label: '昨天', total: 342,
    bills: [
      { id: 4, amount: 128, category: '购物', subCategory: '数码', kind: 'EXPENSE', time: '20:15', remark: '外接键盘' },
      { id: 5, amount: 88, category: '娱乐', kind: 'EXPENSE', time: '21:02', remark: '电影票' },
      { id: 6, amount: 126, category: '三餐', kind: 'EXPENSE', time: '12:30', remark: '周末聚餐' },
    ],
  },
  {
    date: '2026-08-03', label: '8月3日', total: 35.5,
    bills: [
      { id: 7, amount: 12.5, category: '三餐', kind: 'EXPENSE', time: '08:30' },
      { id: 8, amount: 15, category: '交通', kind: 'EXPENSE', time: '18:44' },
      { id: 9, amount: 8, category: '零食', kind: 'EXPENSE', time: '15:12' },
    ],
  },
]

export type DemoAccountKind = 'asset' | 'liability'

export interface DemoAccount {
  id: number
  name: string
  balance: number  // 期初 + 账单派生后的当前余额（正向数字）
  iconColor: string
  kind: DemoAccountKind
}

export const demoAccounts: DemoAccount[] = [
  { id: 1, name: '微信', balance: 2840.36, iconColor: '#28C145', kind: 'asset' },
  { id: 2, name: '支付宝', balance: 5230.14, iconColor: '#06B4FD', kind: 'asset' },
  { id: 3, name: '信用卡', balance: 1380.5, iconColor: '#EF4444', kind: 'liability' },
]

// 净资 = 资产 - 负债
export const demoNetAssets = 6690.0

export interface DemoBudgetCategory { name: string; amount: number; color: string }

export interface DemoBudget {
  monthLabel: string
  spent: number
  total: number
  byCategory: DemoBudgetCategory[]
}

export const demoBudget: DemoBudget = {
  monthLabel: '8月', spent: 3520, total: 5000,
  byCategory: [
    { name: '三餐', amount: 1450, color: '#7EC1FC' },
    { name: '交通', amount: 480, color: '#CA3032' },
    { name: '购物', amount: 920, color: '#04A433' },
    { name: '娱乐', amount: 670, color: '#F59E0B' },
  ],
}

export interface DemoInsight {
  monthLabel: string
  summary: string
  highlights: string[]
}

export const demoInsight: DemoInsight = {
  monthLabel: '8月',
  summary: '本月共支出 ¥3,520，日均 ¥117。三餐占大头，娱乐略有回升；整体符合预算，继续保持。',
  highlights: [
    '三餐支出占 41%，比上月下降 6%',
    '交通支出较平稳，打车频次减少 2 次',
    '本月记账最活跃：连续 12 天打卡',
  ],
}
```

- [ ] **Step 3: Run 测试**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/mock/__tests__/demo-data.test.ts
```
Expected: 4 项通过。

- [ ] **Step 4: Commit**

```bash
git add web/src/mock/demo-data.ts web/src/mock/__tests__/demo-data.test.ts
git commit -m "feat(web): 落地页 Mock 数据层 demo-data.ts + 结构校验测试"
```

---

### Task 3 (B3): 落地页壳 —— Hero/顶部导航/进入控制台 CTA + 页脚

**Files:**
- Modify: `web/src/views/Landing.vue`（替换占位）
- Create: `web/src/views/landing/LandingHero.vue`
- Create: `web/src/views/landing/LandingFooter.vue`
- Test: `web/src/views/__tests__/landing.test.ts`（demo-data 派生断言，避免挂载 IntersectionObserver）

**Interfaces:**
- Consumes: `vue-router` `useRouter`；判断已登录用 `localStorage.getItem('rkl_token')` 直读（不 import `src/stores/**` / `src/api/**`，遵守数据源隔离）。
- Produces: `/` 路由渲染整页；顶部含品牌名 + 「进入控制台」按钮（`router.push('/login')`）；Hero 大标题/副标题/双 CTA（「立即开始记账」→ `/console` 若已登录否则 `/login`；「查看演示」滚动到 `#feature-bookkeeping`）；页脚含版权与「后台登录」链接。Hero 用 `v-reveal` 渐入。

- [ ] **Step 1: 写测试 `web/src/views/__tests__/landing.test.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { demoDays, demoAccounts, demoBudget } from '../../mock/demo-data'

describe('landing derived', () => {
  it('总支出动画值 = 今日 + 昨日 totals（演示用）', () => {
    // Hero 的 count-up 目标取 demoDays 前两项的 total 之和（今日 68.5 + 昨日 342）
    const recent = demoDays.slice(0, 2)
    const sum = recent.reduce((s, d) => s + d.total, 0)
    expect(sum).toBeCloseTo(410.5)
  })
  it('净资 = 资产 - 负债', () => {
    const asset = demoAccounts.filter((a) => a.kind === 'asset').reduce((s, a) => s + a.balance, 0)
    const liability = demoAccounts.filter((a) => a.kind === 'liability').reduce((s, a) => s + a.balance, 0)
    expect(asset - liability).toBeGreaterThan(0)
  })
  it('预算进度百分比在 0-100', () => {
    const pct = Math.round((demoBudget.spent / demoBudget.total) * 100)
    expect(pct).toBeGreaterThanOrEqual(0)
    expect(pct).toBeLessThanOrEqual(100)
  })
})
```

- [ ] **Step 2: 写 `web/src/views/landing/LandingHero.vue`**

```vue
<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

// 数据源隔离：落地页不 import src/stores/**、src/api/**. 已登录判断直接读 localStorage（与 http.ts 的 rkl_token 一致）。
function cta() { router.push(localStorage.getItem('rkl_token') ? '/console' : '/login') }
function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }) }
</script>

<template>
  <section class="hero" v-reveal>
    <span class="hero-chip">个人记账 · 资产 · AI 洞察</span>
    <h1 class="hero-title">把钱记在日本，<br /><span class="grad">每一笔都值得被看见</span></h1>
    <p class="hero-sub">快速记账、收入支出一目了然；资产净额、月度预算、AI 月结复盘，一个控制台全搞定。</p>
    <div class="hero-cta">
      <button class="btn btn-primary" @click="cta">进入控制台</button>
      <button class="btn btn-ghost" @click="scrollTo('feature-bookkeeping')">查看演示 ↓</button>
    </div>
    <div class="hero-stats">
      <div class="stat"><span class="stat-num amount">¥38,240</span><span class="stat-label">本月记账</span></div>
      <div class="stat"><span class="stat-num amount">¥6,690</span><span class="stat-label">净资</span></div>
      <div class="stat"><span class="stat-num">+12</span><span class="stat-label">连续记账天数</span></div>
    </div>
  </section>
</template>

<style scoped>
.hero { max-width: 1080px; margin: 0 auto; padding: 72px 24px 40px; text-align: center; }
.hero-chip { display: inline-block; padding: 6px 14px; border-radius: 999px; background: var(--primary-soft); color: var(--primary); font-size: 13px; font-weight: 600; margin-bottom: 24px; }
.hero-title { font-size: clamp(34px, 6vw, 60px); line-height: 1.1; font-weight: 800; letter-spacing: -0.02em; margin: 0 0 20px; }
.hero-title .grad { background: linear-gradient(120deg, var(--income), var(--primary) 60%, var(--expense)); -webkit-background-clip: text; background-clip: text; color: transparent; }
.hero-sub { font-size: 17px; color: var(--muted); max-width: 620px; margin: 0 auto 32px; line-height: 1.6; }
.hero-cta { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; margin-bottom: 56px; }
.btn { padding: 13px 22px; border-radius: 12px; border: none; font-size: 15px; font-weight: 600; cursor: pointer; transition: transform .12s var(--ease), box-shadow .12s var(--ease); }
.btn:active { transform: scale(.97); }
.btn-primary { background: var(--primary); color: #0b2b44; box-shadow: 0 4px 18px rgba(126,193,252,.35); }
.btn-ghost { background: transparent; color: var(--text); border: 1px solid var(--border); }
.hero-stats { display: flex; justify-content: center; gap: clamp(16px, 4vw, 48px); flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; gap: 6px; }
.stat-num { font-size: 26px; font-weight: 700; }
.stat-label { font-size: 13px; color: var(--muted); }
@media (max-width: 640px) { .hero { padding: 48px 16px 32px; } .hero-title { font-size: clamp(30px, 8vw, 40px); } }
</style>
```

- [ ] **Step 3: 写 `web/src/views/landing/LandingFooter.vue`**

```vue
<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <footer class="landing-footer">
    <div class="footer-brand">
      <strong>RinklNote</strong>
      <span class="footer-sub">记账 · 资产 · AI 洞察</span>
    </div>
    <nav class="footer-links">
      <button class="link" @click="router.push('/login')">后台登录</button>
      <span class="dot">·</span>
      <span class="muted">© 2026 RinklNote</span>
    </nav>
  </footer>
</template>

<style scoped>
.landing-footer { border-top: 1px solid var(--border-light); padding: 28px 24px 40px; display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; max-width: 1080px; margin: 40px auto 0; }
.footer-brand { display: flex; align-items: baseline; gap: 12px; }
.footer-sub { font-size: 13px; color: var(--muted); }
.footer-links { display: flex; align-items: center; gap: 8px; }
.link { background: none; border: none; color: var(--muted); cursor: pointer; font-size: 14px; }
.link:hover { color: var(--primary); }
.dot { color: var(--border); }
.muted { color: var(--muted); font-size: 14px; }
</style>
```

- [ ] **Step 4: 写 `web/src/views/Landing.vue`（替换占位）**

```vue
<script setup lang="ts">
import LandingHero from './landing/LandingHero.vue'
import LandingFooter from './landing/LandingFooter.vue'
import BookkeepingDemo from './landing/BookkeepingDemo.vue'
import AssetsDemo from './landing/AssetsDemo.vue'
import AiDemo from './landing/AiDemo.vue'
</script>

<template>
  <div class="landing">
    <header class="landing-header">
      <span class="brand">RinklNote</span>
      <router-link class="btn-cta" to="/login">进入控制台</router-link>
    </header>

    <LandingHero />

    <div class="feature-bg" v-reveal>
      <section id="feature-bookkeeping" class="feature">
        <BookkeepingDemo />
      </section>
      <section id="feature-assets" class="feature alt">
        <AssetsDemo />
      </section>
      <section id="feature-ai" class="feature">
        <AiDemo />
      </section>
    </div>

    <LandingFooter />
  </div>
</template>

<style scoped>
.landing { min-height: 100vh; }
.landing-header { position: sticky; top: 0; z-index: 50; display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; backdrop-filter: blur(8px); background: color-mix(in srgb, var(--bg) 78%, transparent); border-bottom: 1px solid var(--border-light); }
.brand { font-weight: 800; font-size: 18px; letter-spacing: -0.01em; }
.btn-cta { padding: 9px 16px; border-radius: 10px; background: var(--primary); color: #0b2b44; font-weight: 600; font-size: 14px; }
.feature-bg { background: radial-gradient(1200px 500px at 50% -10%, rgba(126,193,252,.16), transparent 60%); }
.feature { max-width: 1080px; margin: 0 auto; padding: 64px 24px; }
.feature.alt { background: rgba(126,193,252,.05); }
@media (max-width: 640px) { .feature { padding: 44px 16px; } }
</style>
```

> 注：Landing.vue 引用了 `./landing/BookkeepingDemo.vue`/`AssetsDemo.vue`/`AiDemo.vue`，本任务暂未创建 —— **先建三个最小占位组件**（下一任务逐个填实），或本任务暂不 import、分步落。为让 typecheck 通过，本任务先创建三个最小可渲染占位组件：

```vue
<!-- BookkeepingDemo.vue 占位 -->
<script setup lang="ts"></script>
<template><div>记账占位</div></template>
```

```vue
<!-- AssetsDemo.vue 占位 -->
<script setup lang="ts"></script>
<template><div>资产占位</div></template>
```

```vue
<!-- AiDemo.vue 占位 -->
<script setup lang="ts"></script>
<template><div>AI占位</div></template>
```

- [ ] **Step 5: 整理落地页组件目录**：上述三个 demo 占位组件存放于 `web/src/views/landing/` 下。

- [ ] **Step 6: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误。

- [ ] **Step 7: Run 测试**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run src/views/__tests__/landing.test.ts
```
Expected: 3 项通过。

- [ ] **Step 8: Commit**

```bash
git add web/src/views/Landing.vue web/src/views/landing/LandingHero.vue web/src/views/landing/LandingFooter.vue web/src/views/landing/BookkeepingDemo.vue web/src/views/landing/AssetsDemo.vue web/src/views/landing/AiDemo.vue web/src/views/__tests__/landing.test.ts
git commit -m "feat(web): 落地页壳 —— Hero/顶部导航/进入控制台 CTA + 页脚 + 三个 demo 区占位"
```

---

### Task 4 (B4): 记账能力区 BookkeepingDemo（快速记一笔 + 账单流水 + countUp 动效）

**Files:**
- Modify: `web/src/views/landing/BookkeepingDemo.vue`（替换占位）

**Interfaces:**
- Consumes: `mock/demo-data.ts` 的 `demoDays`；`utils/countUp` 的 `countUp`；`utils/format` 的 `formatMoney`。
- Produces: 左侧文案（标题/卖点） + 右侧「App 界面数字图」（手机框内：快速记一笔表单示意 + 按日分组的账单流水），金额用 `countUp` 动效。

- [ ] **Step 1: 写 `web/src/views/landing/BookkeepingDemo.vue`**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { demoDays } from '../../mock/demo-data'
import { countUp } from '../../utils/countUp'
import { formatMoney } from '../../utils/format'

const totalEl = ref<HTMLSpanElement | null>(null)
onMounted(() => {
  // 演示动画：总支出 = 最近几天合计
  const sum = demoDays.reduce((s, d) => s + d.total, 0)
  if (totalEl.value) countUp(totalEl.value, sum, 'landing:exp', (n) => formatMoney(n), 800)
})
</script>

<template>
  <div class="feature-grid">
    <div class="feature-copy" v-reveal>
      <span class="eyebrow">记账 · 账单流水</span>
      <h2>三秒记一笔，账单清清楚楚</h2>
      <p class="lead">支出收入用颜色分清，分类、账户、备注随手下单；账单按日归组，金额一目了然。</p>
      <ul class="points">
        <li>收支类型切换，语义色即刻区分</li>
        <li>分类 + 子分类，快捷记账自动带入</li>
        <li>账单日/月分组，localStorage 长存</li>
      </ul>
    </div>

    <div class="demo-phone" v-reveal>
      <div class="phone-card">
        <div class="phone-title">
          <span>快速记账</span>
          <span class="pill exp">支出</span>
        </div>
        <div class="amount-box">
          <span class="cny">¥</span>
          <span ref="totalEl" class="amount-big amount">0.00</span>
        </div>
        <div class="cat-row">
          <span class="cat on">三餐</span><span class="cat">交通</span><span class="cat">购物</span><span class="cat">娱乐</span>
        </div>
        <div class="bill-list">
          <div v-for="d in demoDays" :key="d.date" class="day-group">
            <div class="day-label"><span>{{ d.label }}</span><span class="day-total amount">¥{{ d.total.toFixed(2) }}</span></div>
            <div v-for="b in d.bills" :key="b.id" class="bill-row">
              <span class="bill-cat">{{ b.category }}</span>
              <span class="bill-remark">{{ b.remark ?? '' }}</span>
              <span class="bill-amount amount" :class="b.kind === 'EXPENSE' ? 'exp' : 'inc'">{{ b.kind === 'EXPENSE' ? '-' : '+' }}¥{{ b.amount.toFixed(2) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.feature-grid { display: grid; grid-template-columns: 1fr 1.1fr; gap: clamp(24px, 5vw, 64px); align-items: center; }
.feature-copy .eyebrow { font-size: 13px; color: var(--income); font-weight: 600; letter-spacing: .04em; }
.feature-copy h2 { font-size: clamp(26px, 4vw, 42px); line-height: 1.15; margin: 12px 0 16px; font-weight: 800; letter-spacing: -0.02em; }
.feature-copy .lead { color: var(--muted); line-height: 1.7; margin: 0 0 20px; font-size: 16px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.points li { position: relative; padding-left: 24px; color: var(--text); font-size: 15px; }
.points li::before { content: '✓'; position: absolute; left: 0; color: var(--primary); font-weight: 700; }
.demo-phone { display: flex; justify-content: center; }
.phone-card { width: min(100%, 340px); background: var(--card); border-radius: 24px; padding: 18px 16px; box-shadow: var(--shadow); border: 1px solid var(--border-light); }
.phone-title { display: flex; justify-content: space-between; align-items: center; font-weight: 700; margin-bottom: 8px; }
.pill { padding: 2px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; }
.pill.exp { background: rgba(202,48,50,.1); color: var(--expense); }
.amount-box { display: flex; align-items: baseline; gap: 4px; margin-bottom: 12px; }
.amount-big { font-size: 34px; font-weight: 800; }
.cny { font-size: 20px; color: var(--muted); }
.cat-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.cat { padding: 6px 12px; border-radius: 10px; background: var(--card); border: 1px solid var(--border); font-size: 13px; color: var(--muted); }
.cat.on { background: var(--primary-soft); border-color: transparent; color: var(--primary); font-weight: 600; }
.bill-list { display: flex; flex-direction: column; gap: 10px; max-height: 300px; overflow-y: auto; }
.day-group { border-top: 1px solid var(--border-light); padding-top: 10px; }
.day-label { display: flex; justify-content: space-between; font-size: 12px; color: var(--muted); margin-bottom: 6px; }
.bill-row { display: grid; grid-template-columns: 90px 1fr auto; gap: 8px; align-items: center; padding: 6px 0; font-size: 13px; }
.bill-cat { font-weight: 600; }
.bill-remark { color: var(--muted); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bill-amount.exp { color: var(--expense); }
.bill-amount.inc { color: var(--income); }
@media (max-width: 820px) { .feature-grid { grid-template-columns: 1fr; } }
</style>
```

- [ ] **Step 2: Run typecheck**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run typecheck
```
Expected: 无错误（`countUp(el, target, key, fmt, dur)` 参数匹配）。

- [ ] **Step 3: Commit**

```bash
git add web/src/views/landing/BookkeepingDemo.vue
git commit -m "feat(web): 落地页记账能力区 —— 快速记一笔 + 账单流水 + countUp 动效"
```

---

### Task 5 (B5): 资产能力区 AssetsDemo（账户列表 + 期初/派生余额 + 净资/负债分区）

**Files:**
- Modify: `web/src/views/landing/AssetsDemo.vue`（替换占位）

**Interfaces:**
- Consumes: `mock/demo-data.ts` 的 `demoAccounts`、`demoNetAssets`；`utils/format` 的 `formatMoney`。
- Produces: 资产账户卡片列表（期初+派生余额）、净资/负债分区展示。

- [ ] **Step 1: 写 `web/src/views/landing/AssetsDemo.vue`**

```vue
<script setup lang="ts">
import { demoAccounts, demoNetAssets } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'

const assets = demoAccounts.filter((a) => a.kind === 'asset')
const debts = demoAccounts.filter((a) => a.kind === 'liability')
</script>

<template>
  <div class="feature-grid">
    <div class="demo-phone" v-reveal>
      <div class="phone-card">
        <div class="phone-title"><span>资产管理</span><span class="pill">净资 ¥{{ formatMoney(demoNetAssets) }}</span></div>
        <div class="net-card">
          <span class="net-label">总资产</span>
          <span class="net-value amount">¥{{ formatMoney(demoAccounts.filter((a) => a.kind === 'asset').reduce((s, a) => s + a.balance, 0)) }}</span>
        </div>
        <div class="acct-list">
          <div v-for="a in assets" :key="a.id" class="acct-row">
            <span class="avatar" :style="{ background: a.iconColor }">{{ a.name[0] }}</span>
            <span class="acct-name">{{ a.name }}</span>
            <span class="acct-bal amount">{{ formatMoney(a.balance) }}</span>
          </div>
        </div>
        <div class="liab-title">负债</div>
        <div class="acct-list">
          <div v-for="a in debts" :key="a.id" class="acct-row">
            <span class="avatar liab" :style="{ background: a.iconColor }">{{ a.name[0] }}</span>
            <span class="acct-name">{{ a.name }}</span>
            <span class="acct-bal liab amount">-{{ formatMoney(a.balance) }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="feature-copy" v-reveal>
      <span class="eyebrow">资产 · 收支平衡</span>
      <h2>明确知道你有多少，欠多少</h2>
      <p class="lead">账户期初值 + 每一笔账单自动派生最新余额；资产与负债分开，净资清晰可见。</p>
      <ul class="points">
        <li>银行卡 / 微信 / 信用卡多账户统一管理</li>
        <li>收入支出自动滚入账户余额</li>
        <li>资产 - 负债 = 真实净资</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.feature-grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: clamp(24px, 5vw, 64px); align-items: center; }
.feature-copy .eyebrow { font-size: 13px; color: var(--expense); font-weight: 600; letter-spacing: .04em; }
.feature-copy h2 { font-size: clamp(26px, 4vw, 42px); line-height: 1.15; margin: 12px 0 16px; font-weight: 800; letter-spacing: -0.02em; }
.feature-copy .lead { color: var(--muted); line-height: 1.7; margin: 0 0 20px; font-size: 16px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.points li { position: relative; padding-left: 24px; color: var(--text); font-size: 15px; }
.points li::before { content: '✓'; position: absolute; left: 0; color: var(--primary); font-weight: 700; }
.demo-phone { display: flex; justify-content: center; }
.phone-card { width: min(100%, 340px); background: var(--card); border-radius: 24px; padding: 18px 16px; box-shadow: var(--shadow); border: 1px solid var(--border-light); }
.phone-title { display: flex; justify-content: space-between; align-items: center; font-weight: 700; margin-bottom: 12px; }
.pill { padding: 2px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; background: var(--primary-soft); color: var(--primary); }
.net-card { background: var(--primary); color: #0b2b44; border-radius: 14px; padding: 14px 16px; margin-bottom: 16px; display: flex; justify-content: space-between; align-items: baseline; }
.net-label { font-weight: 600; opacity: .85; }
.net-value { font-size: 24px; font-weight: 800; }
.acct-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.acct-row { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 10px; border: 1px solid var(--border-light); border-radius: 12px; }
.avatar { width: 34px; height: 34px; border-radius: 50%; color: #fff; display: grid; place-items: center; font-weight: 700; font-size: 15px; }
.avatar.liab { color: #fff; }
.acct-name { font-weight: 600; font-size: 14px; }
.acct-bal { font-weight: 700; }
.acct-bal.liab { color: var(--expense); }
.liab-title { font-size: 12px; color: var(--muted); margin-bottom: 6px; }
@media (max-width: 820px) { .feature-grid { grid-template-columns: 1fr; } }
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
git add web/src/views/landing/AssetsDemo.vue
git commit -m "feat(web): 落地页资产能力区 —— 账户列表 + 净资/负债分区"
```

---

### Task 6 (B6): AI 能力区 AiDemo（预算进度环 + AI 总结卡片 + 语音识别输入示意）

**Files:**
- Modify: `web/src/views/landing/AiDemo.vue`（替换占位）

**Interfaces:**
- Consumes: `mock/demo-data.ts` 的 `demoBudget`、`demoInsight`；`utils/format` 的 `formatMoney`。
- Produces: 月度预算进度环、AI 记账总结卡片、语音识别输入区示意。

- [ ] **Step 1: 写 `web/src/views/landing/AiDemo.vue`**

```vue
<script setup lang="ts">
import { demoBudget, demoInsight } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'

const pct = Math.round((demoBudget.spent / demoBudget.total) * 100)
const C = 2 * Math.PI * 40 // ring circumference (r=40)
const dash = (C * pct) / 100
</script>

<template>
  <div class="feature-grid">
    <div class="feature-copy" v-reveal>
      <span class="eyebrow">AI · 语音 · 预算</span>
      <h2>AI 帮你复盘，预算提醒你别超支</h2>
      <p class="lead">月结自动生成总结与建议；语音说出「午餐 25 块」就能记一笔；预算进度环时刻提醒。</p>
      <ul class="points">
        <li>月结 / 异常 / 习惯 AI 洞察一键看完</li>
        <li>语音识别记账，动口不动手</li>
        <li>月度预算进度，超支预警</li>
      </ul>
    </div>

    <div class="demo-phone" v-reveal>
      <div class="phone-card">
        <div class="phone-title"><span>AI 记账总结</span><span class="pill ai">{{ demoInsight.monthLabel }}</span></div>
        <div class="insight-card">
          <p class="insight-summary">{{ demoInsight.summary }}</p>
          <div v-for="(h, i) in demoInsight.highlights" :key="i" class="insight-hl">· {{ h }}</div>
        </div>

        <div class="voice-row">
          <span class="mic">🎤</span>
          <span class="voice-text">“午餐 25 块”</span>
          <span class="voice-hint">语音识别到 → 三餐</span>
        </div>

        <div class="budget-box">
          <svg class="ring" viewBox="0 0 100 100" width="84" height="84">
            <circle cx="50" cy="50" r="40" fill="none" stroke="var(--border-light)" stroke-width="10" />
            <circle cx="50" cy="50" r="40" fill="none"
              :stroke="pct >= 100 ? 'var(--expense)' : 'var(--primary)'" stroke-width="10" stroke-linecap="round"
              :stroke-dasharray="`${dash} ${C}`" transform="rotate(-90 50 50)" />
          </svg>
          <div class="budget-text">
            <div class="budget-label">{{ demoBudget.monthLabel }}预算</div>
            <div class="budget-val amount">¥{{ formatMoney(demoBudget.spent) }} / ¥{{ formatMoney(demoBudget.total) }}</div>
            <div class="budget-sub" :class="{ over: pct >= 100 }">已用 {{ pct }}%{{ pct >= 100 ? ' · 超支预警' : '' }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.feature-grid { display: grid; grid-template-columns: 1fr 1.1fr; gap: clamp(24px, 5vw, 64px); align-items: center; }
.feature-copy .eyebrow { font-size: 13px; color: var(--primary); font-weight: 600; letter-spacing: .04em; }
.feature-copy h2 { font-size: clamp(26px, 4vw, 42px); line-height: 1.15; margin: 12px 0 16px; font-weight: 800; letter-spacing: -0.02em; }
.feature-copy .lead { color: var(--muted); line-height: 1.7; margin: 0 0 20px; font-size: 16px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.points li { position: relative; padding-left: 24px; color: var(--text); font-size: 15px; }
.points li::before { content: '✓'; position: absolute; left: 0; color: var(--primary); font-weight: 700; }
.demo-phone { display: flex; justify-content: center; }
.phone-card { width: min(100%, 340px); background: var(--card); border-radius: 24px; padding: 18px 16px; box-shadow: var(--shadow); border: 1px solid var(--border-light); }
.phone-title { display: flex; justify-content: space-between; align-items: center; font-weight: 700; margin-bottom: 12px; }
.pill.ai { padding: 2px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; background: var(--primary-soft); color: var(--primary); }
.insight-card { background: var(--primary-soft); border-radius: 14px; padding: 14px 16px; margin-bottom: 14px; }
.insight-summary { color: var(--text); font-size: 13px; line-height: 1.6; margin: 0 0 10px; }
.insight-hl { color: var(--muted); font-size: 12px; line-height: 1.7; }
.voice-row { display: flex; align-items: center; gap: 8px; padding: 10px; border: 1px dashed var(--border); border-radius: 12px; margin-bottom: 14px; }
.mic { font-size: 20px; }
.voice-text { font-size: 14px; font-weight: 600; }
.voice-hint { margin-left: auto; font-size: 12px; color: var(--income); }
.budget-box { display: flex; align-items: center; gap: 16px; }
.budget-text { display: flex; flex-direction: column; gap: 4px; }
.budget-label { font-size: 12px; color: var(--muted); }
.budget-val { font-weight: 700; font-size: 15px; }
.budget-sub { font-size: 12px; color: var(--income); }
.budget-sub.over { color: var(--expense); }
@media (max-width: 820px) { .feature-grid { grid-template-columns: 1fr; } }
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
git add web/src/views/landing/AiDemo.vue
git commit -m "feat(web): 落地页 AI 能力区 —— 预算进度环 + AI 总结卡片 + 语音输入示意"
```

---

### Task 7 (B7): 终局冒烟 —— typecheck + build + 全量测试 + 人工验证

**Files:**
- Modify: 无（验证性收尾）

**Interfaces:** Consumes: 全部先前任务。

- [ ] **Step 1: Run 全量测试**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npx vitest run
```
Expected: 全绿（Plan A 20 项 + 本计划新增 demo-data 4 项 + landing 3 项 + 既有）。`src/views/__tests__/landing.test.ts`、`src/mock/__tests__/demo-data.test.ts` 应全部通过。

- [ ] **Step 2: Run typecheck + build**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run build
```
Expected: `vue-tsc --noEmit` 通过 + `vite build` 成功产出 `web/dist/`。若因 `IntersectionObserver` 在 jsdom 无定义导致 build 警告可忽略（运行时浏览器有）。

- [ ] **Step 3: 人工验证（`npm run dev` 后浏览）**

Run:
```bash
cd "D:/Codes/RinklNote/web" && npm run dev
```
- 打开 `http://localhost:5173/`：落地页渲染，Hero 大标题渐入，三区块内容完整，金钱数字有 countUp 动效。
- 点「进入控制台」→ 跳 `/login`；点 Hero「查看演示」→ 平滑滚动到记账区。
- 切换主题（`/login` 页右上角或控制台设置）→ `data-theme` 生效，落地页暗黑变量同步。
- 移动端窄屏（<820px）→ 三区块单列、无横向溢出。

- [ ] **Step 4: Commit README 提及（可选）**——无必要代码改动则跳过；若 landing 目录需注释说明则在 `web/README.md` 补一行。

- [ ] **Step 5: 终局 commit（若有额外改动）**

```bash
git add web/...
git commit -m "docs(web): 落地页说明补充"
```

---

## Self-Review

**Spec 覆盖（本 Plan B 对应 spec 哪些）**：
- 落地页公开三方：`/` 单向滚动长页。✅ Task B3（壳）+ B4/B5/B6（三能力区）。
- 三能力区：记账+账单流水（B4）、资产+收支平衡（B5）、AI+语音+预算（B6）。✅
- 强视觉：大字标题/渐变、错落布局、滚动渐入（B3/B4/B5/B6 用 `v-reveal`、"grad" 渐变标题、feature-grid 错落）。✅
- 数据源隔离：全程只 import `mock/demo-data.ts` + `utils/countUp` + `utils/format`，不触 api/store（B4/B5/B6 头部 import 可核对）。✅
- 主题接线：`theme.init()` 在 App.vue 调用（B1）、data-theme CSS 变量落地（B1）。✅
- 「进入控制台」按钮 → `/login`（Hero + 落地页 header 双入口）。✅

**Placeholder 扫描**：Task B3 里 Landing.vue 引用三个 demo 组件 —— 给了「先建最小占位组件」代码块 + 后续任务逐个替换填实，非 TBD。其余步骤均有实际 CSS/TS/模板。无 "类似 Task N" 引用。✅

**Type 一致性**：`countUp(el, target, key, fmt, dur?)` 在 B4 调用参数与 `utils/countUp.ts` 签名一致（`target`,`key`,`fmt`,`dur`）。`formatMoney(n:number)` 签名一致（B4/B5/B6）。`demoDays[].total` 为合计、`demoAccounts` 字段 `kind/balance/iconColor/name` 在 B5 逐字段使用。主题 store 用 `set/toggle/init`（B1 调 `init`）与代码一致。✅

> **执行注意**：`v-reveal` 需 `IntersectionObserver`，浏览器运行时正常；jsdom 测试不挂载组件（demo-data/landing 派生测试不触发 directive）。`color-mix(in srgb, ...)` 为现代 CSS，Chromium/WebKit 支持，旧浏览器降级为透明背景不影响功能。
