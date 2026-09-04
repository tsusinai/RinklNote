# Web 前端重做设计

日期：2026-09-04
范围：`web/` 前端重写（当前单文件 CDN React SPA → 工程化 Vue 3 SPA）
用户发起：`/frontend-design` + `/grill-me 讨论前端更新范围`

## 目标

把现有 `web/index.html`（单文件、CDN React + Recharts、6-tab 消费型记账 SPA）重写为
**落地页（App 展示）→ 登录页 → 个人控制台** 三条路由的现代 SPA。
落地页面向公开访客做 App 能力演示；控制台是登录用户实际管理自己账目的界面（功能等同原 web）。

不再是一个 `index.html` 全包，改为 Vite 工程化输出，交由静态托管。

## 已裁定决策（本轮 grilling 结果）

| 维度 | 选择 |
|------|------|
| 风格基准 | App 规格 + 强视觉（保留 App 根字体/主题色，用显示对比托张力） |
| 构建 | Vite 现代构建 |
| 框架 | 换框架重写 → Vue 3（组合式 API） |
| 状态 | Pinia |
| 路由 | Vue Router |
| 图表 | ECharts（vue-echarts） |
| 结构 | 落地页（Demo 展示、公开）→ 登录页 → 控制台（登录后） |
| Demo 数据 | 落地页用静态 Mock；控制台登录后接真后端 |
| 控制台定位 | 个人用户控制台，功能等同原 web（记账/账单/图表/资产/我的/设置） |

## 架构

- **栈**：Vite + Vue 3 `<script setup>` + Pinia + Vue Router + ECharts(vue-echarts)。
- **三面**：
  1. `/` 落地页（Demo 展示，公开、Mock 数据）
  2. `/login` 登录页
  3. `/console` 控制台（路由守卫，未登录跳 `/login`）
- 落地页与控制台**数据源隔离**：落地页只 import `src/demo-data.js` 的 Mock；控制台走 `src/api/` 真后端封装（`Authorization: Bearer`）。两者 store 互不 import，防止污染。

## 路由结构

```
/          落地页（公开，无需登录）
/login     登录页
/console   控制台（守卫）
/console/… 控制台功能子页（见下）
```

落地页 header 一个「进入控制台」按钮 → `/login` → 登录成功 → `/console`。

## 落地页 —— Demo 展示（公开、Mock 数据）

单向滚动长页。按所选三项 App 能力做演示区块，每块配一块「App 界面数字图」（用真实组件拼的静态界面示意）+ 一段说明文字：

1. **记账 + 账单流水**：快速记一笔界面、账单按日/月分组流水、金额 `countUp` 动效。
2. **资产 + 收支平衡**：资产账户列表、期初+账单派生余额、净资/负债分区。
3. **AI + 语音 + 预算**：AI 记账总结卡片、语音识别输入区、预算进度环。

### 强视觉方向

- 根字体沿用 App 栈（Inter + Noto Sans SC），语义色沿用 App（收/支/主色）。
- 用**大字号显示标题、渐变/噪点背景氛围、分区错落布局、滚动渐入动画**托住张力。
- **不做**通用 AI 风：不用紫色渐变主打、不用 Space Grotesk 类替代字体、不用 cookie-cutter 卡片模板。
- 落地页数据全部来自 `src/demo-data.js` 静态 Mock，不碰后端、无需登录、不依赖空库。

## 登录页

账号密码登录，调后端 `/api/auth/login`，成功把 token 存 Pinia + `localStorage`，跳 `/console`。
失败给内联错误提示（不再用原生 `alert`）。

## 控制台 —— 个人记账（登录后、真数据）

布局：顶部/侧边导航 + 内容区。功能等同原 web 的 6 tab，每 tab 一页：

| tab | 内容 | 后端对接 |
|-----|------|----------|
| 记账 | 快速记一笔、账单列表、分类/账户选择 | `/api/bills`、`/api/accounts`、categories |
| 账单 | 流水（日/月分组、筛选） | `/api/bills` |
| 图表 | 趋势折线、分类饼图、月度柱状（ECharts） | `/api/bills`、统计 |
| 资产 | 账户列表、期初+派生余额、净资 | `/api/accounts` |
| 我的 | 账户信息、注销、AI 推送开关 | `/api/auth/me` |
| 设置 | 主题、偏好 | settings |

控制台**复用原 web 已验证的核心逻辑**：`fetchAllBills`（composite cursor 分页）、`api()` 封装（401 自动跳登录）、金额 `countUp`、暗黑切换、移动端响应式。这些从原 `web/index.html` 移植（`fetchAllBills` / `api()` 为**既有可复用逻辑，需保持行为一致**，不是重复造轮子）。

## 数据流

- **落地页**：`src/demo-data.js` Mock → 组件，纯展示无副作用。
- **控制台**：`src/api/*`（fetch/axios 封装）→ 真后端 `/api/*`，`Authorization: Bearer`，401 → 跳登录。
- 两层隔离：落地页 import 不到控制台 store，反之亦然。

## 错误处理 / 可访问性

- 请求失败统一走 `api()` 错误分支 → 全局 toast（替代原生 alert/confirm）。
- 保留 App 语义色（收/支红绿）、`prefers-color-scheme` + `data-theme` 暗黑切换、移动端响应式。
- 落地页与控制台均补足可访问性标签（按钮/图例 aria、图表无障碍描述）。

## 部署（关键现状 + 推荐）

**当前现状（已核实）**：`server`（Ktor）只 expose `/api/*` 业务端点——`plugins/` 含 Serialization、
ErrorHandling、Security、Database，**无 staticFiles 静态托管、无 CORS 配置**。
因此 `web/index.html` 不被 Ktor 托管，它作为独立静态 SPA 由**仓库之外的托管**（如 nginx/静态服务）提供，
生产上同 origin 反代 `/api` → Ktor，或前端单独托管 + 跨域。本仓库内**无 nginx/docker 配置**，生产托管细节在仓库外。

**推荐方案**：
- **开发**：`vite.config.js` 配 `server.proxy` 把 `/api` 代理 → Ktor `:8080`，前端代码统一用相对路径 `/api/...`。
- **生产**：`vite build` 产物（`web/dist`）作为静态站对外；`/api` 由同一 origin 反代到 Ktor（沿用现有与 App 的部署拓扑）。
- 迁移路径：迭代期可先部署 `web/dist` 静态文件 + 同元反代，逐步替换旧的 `web/index.html`。

> 部署疑点：本次设计不改服务端托管逻辑，生产托管方式由用户环境决定，迭代阶段以「Vite 构建产物 + 现有静态托管/反代」为准。若用户需要 Ktor 直接托管，可作为后续可选项单独确认。

## 不做的事（YAGNI）

- 不做控制台的「管理用户 / QQ 机器人 / 系统监控」——控制台是个人记账，不是管理员后台（用户已澄清）。
- 不改动 `server`、`app` 的实现（web 后端 API 已存在）。
- 不引第三方 UI 组件库（用 Vue + 自建组件，贴合 App 规格）。
- 不引入 TypeScript（原 web 用 JS；本轮未提 TS，沿用 JS 以控复杂度）——如需 TS 可在实现计划确认。
