# RinklNote Web 控制台 v2 —— 用户端 UI 优化（含动效系统）+ 管理员控制台设计

> 来源：用户桌面 `plan2.md`（2026-09-16 批准）。本文件是实施契约：分支 `feat/web-console-v2`（基于 main @ daebe1a，独立 worktree `D:/Codes/RinklNote-webui`）。各 Wave 的跨文件数据结构/签名以本文为准。

## 背景结论（三端盘点证实）

- **App 动效基准（Motion.kt 全量盘点）**：克制干脆无弹跳——唯一 easing FastOutSlowIn（注释钦定 Web 镜像 `--ease`）；时长谱系 120/220/240/250/300/500/650ms；按压缩放 0.97/120ms（仅 CTA）；二级页 fade+scale(.96→1)/220ms；图表 500ms 纵向生长；呼吸 650ms(1→1.12)；浮层严格串行；转场方向按 tab 序号判定。
- **Web 动效现状**：≈零。`--dur-*` 令牌定义即死（全站零消费）；唯一 Vue transition 是 toast；路由/弹窗/列表全瞬切；全站 0 处 `:focus`；countUp 无 reduced-motion 检查/无 from 值/无取消；ECharts 零动画配置+暗色不适配；index.html 无防白屏、暗色刷新闪白；reveal 指令无 stagger、无 unmounted 清理。
- **Web UX 短板 26 项** + **服务端零权限模型**：`POST /api/qq-bot/config` 与 `PUT /api/insights/suggest-config` 任何登录用户可写；push_log 无读取 API；机器人 WS 状态不可观测、改配置需重启进程。

---

## 一、动效系统（七维规格，对齐 App）

**原则**：克制干脆无弹跳——全站唯一 `--ease`；只动 transform/opacity；时长全部来自令牌；reduced-motion 分级兜底。

1. **令牌层**（theme.css）：`--dur-press:120ms / --dur-expand:220ms / --dur-drawer:240ms / --dur-sheet:250ms / --dur-page:280ms / --dur-indicator:300ms / --dur-chart:500ms / --dur-breath:650ms`、`--press-scale:.97`、`--overlay-in:.96 / --overlay-out:1.02`；原 `--dur-fast/med/slow` 保留为别名并逐处替换硬编码。
2. **页面转场**：RouterView 包 `<Transition>`，按 tab 序号判方向（对齐 App 规则）——前进 `translateX(24px)→0`+fade 280ms，后退反向；Landing↔Login 纯 fade；router 补 `scrollBehavior top`。
3. **弹层**：统一 Modal/ConfirmDialog/Sheet 组件——面板 fade+scale .96→1 进、→1.02 出（220ms）；底部 sheet translateY 250ms；Teleport+Esc+遮罩关闭+焦点圈禁；浮层串行（出完 240ms 再进下一个）。
4. **列表编排**：reveal 指令升级（支持 `v-reveal="delayMs"` stagger、补 unmounted 清理）；Bills 行/设置卡/资产网格进场 stagger；增删用 `<TransitionGroup>`（fade+高度收拢+FLIP move，220ms——对齐 App ContentResize 弹簧让位观感）。
5. **数字**：countUp 重写——from 值滑动（不归零重涨）、rAF 取消、reduced-motion 定格；补齐 Hero 统计、总资产。
6. **图表**：ECharts 受控动画（draw 500ms/update 300ms/cubicOut）；轴文字与配色读 CSS 变量并 watch 主题强制重渲染（修暗色看不清）。
7. **微交互与加载态**：`.pressable`（.97/120ms 仅 CTA）；分类选中 pop（色 220ms+scale 1.03 回落）；开关/chip 220ms；chevron 220ms；预算条 500ms；QQ 状态点呼吸 650ms；全局 `:focus-visible`；hover 补齐；Skeleton shimmer；主题切换平滑过渡；index.html 内联防闪白脚本；`utils/motion.ts#prefersReducedMotion()` 供 JS 侧统一检查。

## 一·补 持续动效背景（已定案：晨雾蓝 + 数据点睛）

- **范围**：Landing/登录页明显可感；控制台不做全页背景、仅卡片级数据光晕；管理端零常驻动效。
- **令牌**：`--glow-1: rgba(126,193,252,.38)`（主蓝）+ `--glow-2/3`（收入绿 .14/暖橙 .12 点缀），暗色 alpha 减半、色温更冷；`--dur-glow-a:90s / --dur-glow-b:70s / --dur-halo:8s`。
- **晨雾光斑**（纯 CSS 零 JS）：`.aurora` fixed 层 z-index:-1，3 个 blob（60vmin + blur(90px) + radial-gradient 渐隐），blob1/2 反相慢漂移、blob3 纯 scale 呼吸 0.92↔1.08；只动 transform/opacity；`<768px` 减为 2 层。落点：Landing hero、登录卡后、能力区。
- **结余呼吸**：总资产卡/Bills 结余卡 `box-shadow` 光晕——本月结余≥0 → 收入绿晕 8s 呼吸；<0 → 支出红晕**静态不呼吸**（负向静默警示）。数据源为已有聚合值，零隐私风险。
- **省电/降级**：`visibilitychange → animation-play-state: paused`；reduced-motion 由现有全局规则自动冻结（纯 CSS 方案零额外代码）。
- **明确不做**：Canvas 粒子（AI 模板风+耗电）、feTurbulence 噪点流、控制台全页背景、管理端常驻动效。

## 二、方案一：用户控制台 UI 优化

### A. 设计系统升级（theme.css/app.css）

对齐 App RinklColors：新增 `--on-expense/--on-income`（消灭 4 处 `#fff` 写死）；暗色收支色 `#FF6B6B/#4CD97B`；图表令牌 `--chart-1…7`=PiePalette `[#7EC1FC,#F97D1D,#04A433,#9B59B6,#F2B134,#CA3032,#B0B0B0]`+`--chart-axis`；表面层级 `--surface-2`。新建 `components/ui/`（Card/Btn/Modal/ConfirmDialog/Sheet/Skeleton/EmptyState/StatCard/内联 SVG 图标集），替换 8 个 settings 子组件重复样式与全部 prompt/confirm。零新增依赖。品牌：小盘头像 PNG 接入侧栏/登录页。

### B. 布局壳重做（ConsoleLayout）

桌面侧栏=品牌区（小盘头像）+导航分组（记账：记账/账单/图表｜资产｜我的：我的/设置）+底部用户卡+退出；移动端底栏 5 tab，「设置/退出」移入我的页（修 slice(0,5)）+46px 顶栏含主题切换；路由加 404 页、`document.title` 随路由、401 跳转带 `?redirect=`。

### C. 页面翻新（动效随页落地）

Bookkeeping：网格响应式修复、skeleton、选中 pop、提交反馈+toast｜Bills：汇总卡跟随筛选、筛选 chips、移动端卡片化、本地分页、行 stagger、TransitionGroup 增删｜Charts：PiePalette+主题联动、空态、周期切换 update 动画、扇区下钻｜Assets：弹层替换 prompt/confirm、总资产 countUp+结余呼吸｜Me：身份卡、退出统一、移动端设置入口｜Settings：统一 Card+三态、卡片 stagger｜Login/Landing：修注册 bug、激活态改主蓝、Hero countUp+晨雾光斑、顶栏 CTA 判断 token。

### D. 硬伤修复清单（必做）

注册不调 register API；移动端无设置/退出；汇总卡口径；chartData 30 天月漂移（改自然月）；CSV 导出浮点除法破坏整数分契约；无 404；Landing CTA；401 丢回跳；scoped `.grid-4` 压响应式；reveal 泄漏；countUp 三缺陷。

## 三、方案二：管理员控制台设计（分期版）

### A. 权限模型（P0）

`ADMIN_IDENTITIES` 环境变量（手机号/userId）；`Security.kt` 加 `requireAdmin`（JWT admin claim+名单双保险）；`MeResponse` 加 `isAdmin`。**收口现存漏洞**：`POST /api/qq-bot/config`、`PUT /api/insights/suggest-config` 移入 requireAdmin（suggest-config 非管理员保留只读，设置页显示「由管理员配置」）。隐私红线：admin 统计只给聚合计数。

### B. 服务端 P0 API（新文件 AdminRoutes.kt + AdminService.kt）

`GET /api/admin/overview`（用户/账单/今日活跃/QQ 绑定计数+DB 类型+LLM/ASR 状态）；`GET /api/admin/users?query=&page=`（只读、掩码手机号）；`GET /api/admin/push-logs`；`GET /api/admin/bot/status`（补 QQBotService/WSClient getter：token 有效期+WS 在线）；顺手修 WS 热重连（保存配置后 restart）。

### C. Web /admin 路由区（独立 AdminLayout）

守卫校验 `user.isAdmin`，非管理员按 404 处理；同一设计系统+深色侧栏变体+「运维」徽标。**P0 四页**：①运维大盘（统计卡+系统信息+机器人离线红条呼吸告警）②用户管理只读（搜索+表格+详情抽屉）③机器人运维（状态卡+配置表单二次确认）④推送历史（筛选+分页）。

### D. P1 蓝图（只写文档不实施）

封禁/解封（disabled 列+token_version 吊销）、重置密码、审计日志、手动推送、suggest-config 管理页。

## 实施安排（Wave 划分）

0. **分支隔离**：worktree `D:/Codes/RinklNote-webui`，分支 `feat/web-console-v2`（已完成）。
1. **W1 设计系统+动效令牌**：theme.css/app.css + components/ui/ 基础件 + utils/motion.ts + countUp 重写 + reveal 升级 + glow/aurora 令牌与 CSS 层 + index.html 防闪白。
2. **W2 布局壳+转场+路由**：ConsoleLayout 重做、404/title/redirect/scrollBehavior。
3. **W3 页面翻新（三代理并行，文件不相交）**：3a Bills+Bookkeeping｜3b Charts+Assets+Me+Settings｜3c Login+Landing；硬伤清零+晨雾光斑落 Landing/登录+结余呼吸落资产/结余卡。
4. **W4 服务端 P0**：requireAdmin+收口+/api/admin/*+WS getter/热重连 + 服务端用例。
5. **W5 Web /admin 区**：AdminLayout+四页。
6. **W6 验证与收尾**：`npm run typecheck && npm run test` 与 `:server:test` 全绿（现有 18+91 测试不回归）；补 vitest 用例（admin 守卫/countUp/motion/chartData 自然月）；FEATURES.md 补条目；AGENTS.md 导航清单补 `/admin`。

**约束**：零新增依赖；UI 文案全中文；金额整数分不动（CSV 导出走 `parseMoneyToMinor/formatMoneyPlain` 契约）；不触碰 `InsightService.naturalQueryContext`/`LearningService.processCorrections`；subagent 不并发跑同一工作树的 Gradle/npm（验证由主会话或当值代理串行执行）。
