# RinklNote 全端优化实施计划（2026-09-18）

> **For Claude:** 使用 Executing Plans 技能逐任务实施本计划。Wave 0 → 4 顺序执行，Wave 内按任务编号顺序；每个任务独立提交，Wave 结束跑三端全量测试后合并 `main`。
> 用户于 2026-09-18 拍板：头脑风暴清单**全部实施**。范围以本文档为准，执行中若发现与仓库现状冲突，先核对再改。

**Goal:** 落地 2026-09-18 头脑风暴全部方向——后端债务清账与运维基础、AI 智能化、Android 录入摩擦与性能、Web 体验、跨端产品大件。

**Architecture:** 不改变现有总体架构：本地优先三端（Android Compose + Room / Ktor + Exposed 服务端 / Vue 3 SPA）+ 四通道 Bot，LWW 同步、整数分金额、`Asia/Shanghai` 业务时区。所有新功能在既有模块内落地；共享账本等超大件在 Wave 4 内先立项细化。

**Tech Stack:** Kotlin 2.0.21 · Compose · Room v16 · Ktor 2.3.13 · Exposed 0.51.1 · Vue 3.5 · Vite 6 · Pinia · ECharts 5 · Gradle 8.13（版本目录）

---

## 0. 总体策略

- **Wave 顺序与理由**：Wave 0 后端债务/运维（确定性最高，为 AI 与产品功能扫清地基）→ Wave 1 AI（服务端为主，基于干净底座）→ Wave 2 Android（用户可感知的摩擦削减）→ Wave 3 Web 快赢 → Wave 4 大件（从小到大，共享账本最后、允许拆独立计划）。
- **分支**：每 Wave 开 `feat/wave<N>-<slug>`；每任务一个提交；Wave 完成后跑三端全量测试 → 合并 `main` → 推 `origin`。合并前按 AGENTS.md Git 工作流重新核对 `git status --short --branch` 与上游。
- **环境前提（Git Bash）**：
  ```bash
  export JAVA_HOME="D:/Codes/AndroidStudio/jbr" GRADLE_USER_HOME="D:/Codes/RinklNote/.gradle-home"
  ```
- **三端测试命令**：
  - Server：`./gradlew :server:test`
  - App：`./gradlew :app:testDebugUnitTest`
  - Web：`cd web && npm run typecheck && npm run test`
- **提交风格**：沿用仓库现有 `<type>(<scope>): 中文描述`；按任务涉及的具体路径暂存，**禁止 `git add -A`**。
- **TDD 约定**：服务端/逻辑类任务先写失败测试再实现（下文每个任务标注「测试先行」点）；纯 UI/文案任务以现有单测不回归 + 真机/浏览器冒烟验收。

---

## Wave 0：后端债务清账与运维基础

### Task 0.1 条件 PUT 改为真乐观锁 UPDATE
**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt`（条件 PUT 分支）
- Test: 新建 `server/src/test/kotlin/com/example/rinklnote/server/BillOptimisticLockTest.kt`

**做法（测试先行）：**
1. 写失败测试（H2）：带过期 `base_updated_at` 的 PUT → 断言 409 且数据未变；匹配 → 200 且 `updated_at` 前进。
2. 将「先读后写」改为单条带版本条件的 UPDATE：Exposed `update({ BillsTable })` + `where { id eq ? and updatedAt eq baseUpdatedAt }`；受影响行数 = 0 时二次区分：记录不存在 → 404，版本不匹配 → 409。
3. 顺带审查 `AccountRoutes.kt` / `BudgetRoutes.kt` 是否同模式，同模式一并修。

**验证：** `./gradlew :server:test` 全绿（含新用例两个分支）。
**提交：** `fix(server): 条件PUT改版本条件UPDATE，并发更新不再丢失`

### Task 0.2 摘掉 fallbackToDestructiveMigration
**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt`（`buildDatabase()` 末尾）

**做法：**
1. 删除 `fallbackToDestructiveMigration()`；确认 `MIGRATION_1_2 … MIGRATION_15_16` 全链仍在且 `addMigrations` 覆盖 1→16。
2. 检查 Room schema 目录 `app/schemas/` 中 16.json 存在（`exportSchema = true`）。
3. 真机冒烟：覆盖安装旧库版本 → 正常迁移启动；全新安装 → 正常建库。

**验证：** `./gradlew :app:testDebugUnitTest` 全绿 + 真机升级冒烟。
**提交：** `fix(app): 移除破坏性迁移兜底，迁移链路全量生效`

### Task 0.3 修复 QQ 用户（无密码）改密崩溃
**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/AuthRoutes.kt` + `server/.../services/UserService.kt` / `PasswordPolicy.kt`（改密入口按实际分布）
- Test: `server/src/test/...` 补用例

**做法（测试先行）：**
1. 失败测试：`password_hash == null` 的用户（QQ 通道注册）提交改密 → 应 200 成功设置新密码（视为首次设密），而非 NPE/500。
2. 实现：改密端点对 null 哈希走「首次设置」分支，跳过旧密码校验，新密码仍过 `PasswordPolicy`（≥6 位且含大小写）。

**验证：** `./gradlew :server:test` 全绿。
**提交：** `fix(server): QQ无密码用户改密走首次设密分支不再崩溃`

### Task 0.4 业务时区统一（清 LocalDate.now()）
**Files:**
- Modify: App 内所有 `LocalDate.now()` 调用点（执行时 `grep -rn "LocalDate.now()" app/src server/src` 出清单逐处替换）；`app/.../util/DateUtil.kt` 增补便捷函数（如 `today()`）
- Test: 受影响 ViewModel 的既有日期用例补边界

**做法：** App 侧统一换 `DateUtil`（`bookkeepingZone()`）派生的今天/昨天；Server 侧统一 `ZoneId.of("Asia/Shanghai")`（可放 `services/Money.kt` 旁建 `TimeUtil` 或复用现有常量）。逐处替换，不引入行为变化以外的调整。

**验证：** 三端测试全绿；真机改系统时区后记账归日正确。
**提交：** `fix: 日期统一走业务时区 Asia/Shanghai`

### Task 0.5 下线废弃端点
**Files:**
- Modify: `server/.../routes/AuthRoutes.kt`（删 `/qq-login` 登录码端点）、`server/.../routes/QQWebhookRoutes.kt`（旧共享密钥协议 webhook；确认 `QQBotWebhookRoutes.kt` 新通道保留）
- Test: 删除对应测试；跑全量

**做法：** 确认三端 UI 已无调用（App 登录页已移除入口，grep 三端确认）后删除路由与关联死代码；`Application.kt` 路由注册同步摘除。

**验证：** `./gradlew :server:test` 全绿；服务端冒烟新 QQ 通道 webhook 正常。
**提交：** `chore(server): 下线废弃的qq-login与旧webhook共享密钥协议`

### Task 0.6 Web 搜索服务端化
**Files:**
- Modify: `server/.../routes/BillRoutes.kt`（新增 `GET /api/bills/search?q=&min=&max=&categoryId=&from=&to=&page=`）；`server/.../services/BillService.kt`
- Modify: `web/src/api/`（bills 域封装加 search）、`web/src/views/console/`（搜索视图改调服务端；当前为全量内存过滤的调用点）
- Test: Server 搜索参数组合用例；Web 既有 Vitest 回归

**做法：** 服务端 Exposed 条件拼接（软删过滤、按用户隔离），分页返回既定 DTO；Web 端将内存过滤切到 API，保留前端输入防抖。金额参数用「元」入参、「分」内部换算（走 `Money.toMinor`）。

**验证：** `./gradlew :server:test` + `cd web && npm run typecheck && npm run test`。
**提交：** `feat(server,web): 账单搜索服务端分页过滤，Web移除全量内存扫描`

### Task 0.7 PushScheduler 失败重试与通道健康度
**Files:**
- Modify: `server/.../services/PushScheduler.kt`（失败入重试队列：内存 + `PushLogTable` 标记，指数退避最多 3 次）
- Modify: `server/.../routes/AdminRoutes.kt`（运维大盘加通道健康度：最近成功/失败/重试中计数）
- Test: 重试逻辑单测（假通道）

**做法：** 失败任务写入内存队列（进程内即可，重启丢弃可接受——有 PushLog 可查）；重试期间状态可见于管理面。

**验证：** 单测 + 生产部署后看推送历史页。
**提交：** `feat(server): 推送失败重试队列与通道健康度展示`

### Task 0.8 admin API 部署生产 + 数据备份 + 最小观测性
**Files:**
- Create: `server/scripts/backup.sh`（H2 `BACKUP TO` / 文件库快照轮转保留 14 份）
- Modify: 部署机（jmbot 118.31.184.221）crontab + `ADMIN_IDENTITIES` 环境变量

**做法：**
1. 部署：推送 `main` 到 `server` 远端部署机（按既有部署流程），配置 `ADMIN_IDENTITIES`（主账号手机号），Web 管理面四页线上验证（只读、掩码）。
2. 备份：每日凌晨 crontab 执行 `backup.sh`；**演练一次恢复**（备份文件还原到临时库启动比对）。
3. 观测性：Ktor 全局异常 hook——未捕获异常时经 PushScheduler 往主账号通道推一条告警（含路径与摘要，不含敏感 body）。

**验证：** 线上管理面可访问；`ls` 备份目录见当日文件；恢复演练通过；人为触发一次异常收到 Bot 告警。
**提交：** `feat(server): 生产部署admin面+每日备份脚本+异常告警`

---

## Wave 1：AI 智能化（服务端为主）

### Task 1.1 Bot 多轮修正（最近一单操作）
**Files:**
- Modify: `server/.../services/BotCommands.kt`（指令常量：改金额/改分类/删上一笔/撤销）、`QQMessageProcessor.kt` / `FeishuMessageProcessor.kt` / `WecomMessageProcessor.kt` / `MpMessageProcessor.kt`（命令分发汇合点——三通道同构，优先抽公共处理函数）
- Modify: `server/.../services/BillService.kt`（按用户查最近一笔未删账单 + 定向更新/软删）
- Test: 命令解析与最近一单操作单测

**做法（测试先行）：** 「最近一单」不新增存储，直接查 DB 该用户最新未删账单；指令命中后执行并回执确认文案（含改前→改后摘要）；无最近账单时给引导文案。四个通道处理器统一走同一个公共执行函数，避免四份复制。

**验证：** 单测覆盖：解析、无账单、成功改/删、软删后不可再删；生产 Bot 冒烟。
**提交：** `feat(server): bot多轮修正——改金额/改分类/删上一笔`

### Task 1.2 个人记忆层（聚合画像 KV）
**Files:**
- Create: `server/.../tables/UserMemoryTable.kt`（user_id + key + value + updated_at，key 形如 `top_merchants`、`habits`）
- Modify: NLU 成功落账后的异步累计钩子（`services/nlu/` 出口处）、`AiAssistService.kt` / `insight/`（prompt 注入 top 商家与习惯摘要）
- Test: 累计聚合与 prompt 摘要生成单测

**做法（测试先行）：** 只存**聚合**：商家名 + 次数 + 首选分类（不含单笔明细与金额），符合隐私红线（红线自查清单见文末）；写入失败不影响落账主流程。LLM prompt 注入摘要限长（≤200 字）。

**验证：** 单测 + 自查 `naturalQueryContext` / `processCorrections` 注入内容仍无明细。
**提交：** `feat(server): 用户聚合画像KV，LLM与bot具备个人记忆`

### Task 1.3 NLU 理解升级
**Files:**
- Modify: `server/.../services/nlu/`（规则引擎：模糊金额区间词表「三四十/几十块」→ [30,40] 取中值落账并在回执标注区间；「跟上次一样/老样子」→ 查记忆层最近同商家记录；商家→类目内置映射 seed + LLM 兜底）
- Test: `server/src/test/` NLU 用例扩展（区间、指代、品牌归类）

**做法（测试先行）：** 指代解析仅在记忆层有唯一近期命中时生效，歧义时回执追问。App 端 `VoiceParser.kt` 本地解析同步加区间词表（仅金额部分，保持轻量）。

**验证：** 服务端 NLU 单测 + App `:app:testDebugUnitTest`。
**提交：** `feat(nlu): 模糊金额区间、上下文指代与商家归类`

### Task 1.4 账单教练（洞察→行动）
**Files:**
- Create: `server/.../services/coach/CoachService.kt`（周聚合输入 → 结构化建议：类型/文案/关联 challenge 或 budget id）
- Modify: `PushScheduler.kt`（周报模板接 CoachService 个性化文案）、`services/insight/`（复用其聚合口径）
- Test: 预算烧穿速度派生 + 建议生成的单测（测试向量与 App `ChallengeEngine` 对齐口径，向量写死在测试里）

**做法（测试先行）：** 服务端复刻「周预算消耗速度 vs 线性基准」派生（App `domain/ChallengeEngine.kt` 同口径，纯函数）；预警阈值：预测月底超支 → 提前提醒；联动 Task 1.2 记忆层做个性化措辞。**隐私红线**：输入仅聚合（分类+金额+日期）。

**验证：** 单测；真机收到一条含个性化建议的周报。
**提交：** `feat(server): 账单教练——烧穿预警与可执行周建议`

### Task 1.5 小盘人格化文案层
**Files:**
- Create: `server/.../services/coach/MascotVoice.kt`（推送文案统一小盘口吻的文案函数/资源）
- Modify: App 成就/挑战/超支文案常量（`app/.../domain/Achievements.kt` 相关 UI 文案、`notification/` 每日报告）
- Test: 文案函数非空与占位替换单测（轻量）

**做法：** 先写「小盘语气指南」进 `docs/`（称呼、口癖、禁止项），再按指南替换四通道推送、App 每日报告、成就解锁文案。贺图使用 `resource/二次元logo/` 现有资产，不新画。

**验证：** 三端测试全绿；真机看每日报告与成就文案。
**提交：** `feat: 小盘人格化——推送/报告/成就文案统一口吻`

---

## Wave 2：Android 录入摩擦与性能

### Task 2.1 系统分享记账
**Files:**
- Modify: `app/src/main/AndroidManifest.xml`（`ACTION_SEND text/plain` intent-filter → `ShareReceiveActivity`）
- Create: `app/.../navigation/ShareReceiveActivity.kt`（解析文本 → 进主界面并打开快速记账预填）
- Modify: `app/.../navigation/BookingOrchestrator.kt` 或 `QuickAddViewModel`（接收预填：金额/分类/备注）
- Test: 文本→预填解析的单测（复用/扩展 `VoiceParser` 规则）

**做法：** 分享文本先走本地 `VoiceParser` 规则；未解析出金额时仍进入抽屉并把原文放入备注。微信/支付宝账单详情「分享」出的文本格式以其真实样例为准（真机采集后写死解析规则 + 用例）。

**验证：** 单测 + 真机从微信/支付宝分享直达预填。
**提交：** `feat(app): 系统分享菜单直达快速记账预填`

### Task 2.2 小票/截图 OCR 记账
**Files:**
- Modify: `gradle/libs.versions.toml`（新增 `mlkit-text-recognition-chinese`，按约定走版本目录）
- Create: `app/.../ui/screen/quickadd/` 内 OCR 入口（拍照/相册选图 → ML Kit 识别 → 金额/日期/商家候选 chips → 点选预填）
- Test: 识别文本→金额候选提取的单测（纯字符串解析部分）

**做法：** 不新建完整页面，作为快速记账抽屉内「相机」chip 的二级流；金额候选取识别文本中的 `¥?\d+(\.\d{1,2})?` 序列按置信度排序；识别全程端上离线，图片不上传。

**验证：** 单测 + 真机拍 3 种小票样例。
**提交：** `feat(app): 小票OCR离线识别预填记账`

### Task 2.3 支付通知监听（默认关）
**Files:**
- Create: `app/.../notification/PayNotifyListenerService.kt`（`NotificationListenerService`，白名单包名：微信/支付宝/主流银行）
- Modify: `app/.../data/local/SettingsManager.kt`（开关键，默认 false）、`ui/screen/profile/` 设置项、通知动作「记一笔」→ 快速记账预填
- Test: 支付通知文本→金额/商家解析单测

**做法：** 解析完全本地；默认关闭并在开启页明示权限用途；解析失败的通知只弹通用「记一笔」入口不带预填。

**验证：** 单测 + 真机开关与两条真实通知样例。
**提交：** `feat(app): 支付通知监听一键记账（默认关闭）`

### Task 2.4 小组件与快捷方式
**Files:**
- Modify: `app/.../widget/RinklNoteAppWidget.kt`（Glance：加 2 个预设金额快捷 chip，点击直接拉起预填抽屉）
- Create: `app/src/main/res/xml/shortcuts.xml`（长按图标：语音记账 / 快速记账 / 搜账单）+ Manifest 引用
- Test: 既有 App 单测回归

**做法：** 预设金额走 `SettingsManager` 可配置（默认 ¥10/¥50）；`deepLink` 复用现有路由。

**验证：** 真机小组件点击链路 + 长按快捷方式。
**提交：** `feat(app): 桌面小组件快捷金额与长按快捷方式`

### Task 2.5 离线 ASR 调研 + 条件实施
**Files:**
- Modify: `app/.../util/VoiceRecorder.kt` / `VoiceInputUtil.kt`（如需切换引擎）

**做法（先调研后动手）：** 真机验证现有 `SpeechRecognizer(zh-CN)` 飞行模式可用性；达标（可离线 + 识别率可接受）则仅补文档与设置页「离线语音」说明，不引新依赖；不达标再评估 `sherpa-onnx`（依赖先进 `libs.versions.toml`）。**本任务产出 = 调研结论 + 最小改动。**

**验证：** 飞行模式真机语音记账通过（或记录不达标证据与替代方案）。
**提交：** `docs(app)/feat(app): 语音离线能力调研结论（按结果）`

### Task 2.6 常去地点智能建议（记账时）
**Files:**
- Modify: `app/.../data/db/`（Room v17：`places` 表 id/name/lat/lng/category_id/last_used；`MIGRATION_16_17`）
- Modify: `app/.../util/LocationGrabber.kt`（落账带位置时异步累计到 places，距离阈值合并）、快速记账位置 chip（接近已知地点 → 建议该地点 + 常用分类）
- Test: 位置合并与最近地点匹配的单测（纯几何计算）

**做法：** **不做后台定位**，只在用户主动打点那一刻给建议，权限模型不变。Room v17 迁移遵循 v13 教训（重建顺序、外键）。

**验证：** `:app:testDebugUnitTest` + 真机两次同类目打点后第三次出现建议。
**提交：** `feat(app): 常去地点表与记账时智能建议（Room v17）`

### Task 2.7 年度账单分享图
**Files:**
- Modify: `app/.../util/BillImageExporter.kt`（年度版式：12 月热力格 + 年总收支 + Top5 分类 + 小盘贺词）
- Modify: `ui/screen/profile/` 或挑战 hub 入口（「年度账单」按钮）
- Test: 数据聚合（月合计/Top 分类）单测

**做法：** 复用现有 Canvas 绘制与 FileProvider 分享链路；年份可选，默认当年。

**验证：** 单测 + 真机导出图片目检。
**提交：** `feat(app): 年度账单分享图`

### Task 2.8 启动与列表性能
**Files:**
- Modify: `app/build.gradle.kts`（baseline profile 插件走版本目录；如工程复杂度过高则降级为仅 LazyColumn/Room 优化并记录原因）
- Modify: 账单列表 LazyColumn（`key = bill.id`、`contentType`）、`dao/BillDao.kt` 索引复核（`date`、`deleted`、`server_id` 组合）

**做法：** 先测后改：记录冷启动基线（adb `am start -W` 或 Macrobenchmark）→ 优化 → 对比。**性能红线：视觉一点不动。**

**验证：** 启动时间对比数据留档 `docs/`；`:app:testDebugUnitTest` 全绿。
**提交：** `perf(app): 列表key/索引与启动优化（附前后数据）`

---

## Wave 3：Web 快赢

### Task 3.1 PWA 化
**Files:**
- Modify: `web/vite.config.ts` + `web/package.json`（`vite-plugin-pwa`：manifest、图标取 `resource/` 官方资产、precache 应用壳）
- Modify: `web/index.html`（theme-color、apple-touch-icon）
- Test: `npm run build` 通过 + 既有 Vitest 回归

**做法：** service worker 只做静态资源 precache + 导航兜底；**不做**账单数据离线缓存（同步语义复杂度不值得，YAGNI）。手机加桌面可独立打开即可。

**验证：** `npm run build` → 产物部署后 Lighthouse PWA 项通过；真机加桌面打开。
**提交：** `feat(web): PWA——manifest+离线应用壳`

### Task 3.2 ECharts 按需引入
**Files:**
- Modify: `web/src/utils/echartsTheme.ts` 及各图表组件（`echarts/core` + 按需注册 `LineChart/BarChart/PieChart` + 用到的组件/渲染器）
- Test: 构建产物体积对比留档

**做法：** 抽一个 `web/src/utils/echarts.ts` 统一导出按需注册后的 echarts，全局替换 import；构建前后 `dist` 体积对比写进提交说明。

**验证：** `npm run build` + `npm run test`；图表逐一目检无缺组件（tooltip/legend/grid）。
**提交：** `perf(web): ECharts按需注册，bundle瘦身`

### Task 3.3 粒子星空帧率自适应
**Files:**
- Modify: `web/src/utils/particleField.ts`（RAF 帧时间监测：连续低于 45fps → 密度/速度降档；`document.visibilitychange` 暂停恢复；尊重 `prefers-reduced-motion` 直接静态星空）
- Modify: `web/src/components/ParticleBackground.vue`（接线）
- Test: 降档策略的纯函数单测（给定帧时间序列 → 档位）

**做法：** 视觉基调不变（用户 2026-09-16 确认的浓密度保持默认档），只在性能不足时降档。降档策略实现为**可复用模块**，Task 3.6 的墨晕热力场共用同一套降档。

**验证：** 单测 + 低端设备/DevTools CPU 4x throttle 目检不掉帧。
**提交：** `perf(web): 星空粒子帧率自适应与页签隐藏暂停`

### Task 3.4 Ctrl+K 命令面板 + 键盘流记账
**Files:**
- Create: `web/src/components/CommandPalette.vue`（路由跳转 + 常用动作：记一笔/搜索/导出）
- Modify: `web/src/views/console/Bookkeeping.vue`（数字键直输金额、`Enter` 确认、`Esc` 关抽屉——复用现有确认流程）；`ConsoleLayout.vue` 挂全局快捷键与入口
- Test: 快捷键路由解析与金额直输的组合单测

**做法：** 快捷键注册做防冲突（输入框聚焦时不拦截）；面板动作表用配置数组便于扩充。

**验证：** Vitest + 浏览器手测全套键位。
**提交：** `feat(web): Ctrl+K命令面板与记账页键盘流`

### Task 3.5 Web 月度分享图导出
**Files:**
- Create: `web/src/utils/shareImage.ts`（手写 Canvas 绘制：月总收支 + 分类占比 + 热力条，对齐 App 分享图版式；输出 PNG 下载）
- Modify: `web/src/views/console/Charts.vue` 或 `Bookkeeping.vue`（「导出分享图」按钮）
- Test: 月度聚合数据的单测（复用 `chartData.ts` 既有用例口径）

**做法：** 零新依赖手写 Canvas（沿用 Web 端零依赖偏好）；配色走 `theme.css` CSS 变量读取，暗色模式下同样成立。

**验证：** Vitest + 两套主题下导出图片目检。
**提交：** `feat(web): 月度账单分享图导出`

### Task 3.6 Landing 墨晕热力场（C2 墨晕纸纹 + E2 热力波场）
> 设计口径 2026-09-18 已确认（头脑风暴六方向 → 用户选定 C2+E2 融合），见任务内参数；建议排在 Task 3.3 之后实施以复用其降档策略。

**Files:**
- Create: `web/src/utils/inkField.ts`（纯逻辑：手写 value noise + fBm 2~3 octaves + 域扭曲 + 阈值/羽化映射 + 调色板，零依赖；可调参数集中文件顶部常量表）
- Create: `web/src/components/InkFieldBackground.vue`（挂载/生命周期，骨架镜像 `ParticleBackground.vue`）
- Modify: `web/src/views/Landing.vue`（接入新组件，移除静态 `.aurora` 晨雾光斑层）
- Test: `web/src/utils/__tests__/inkField.spec.ts`

**做法：**
1. **概念**：宣纸底上墨随热流缓慢晕开——warp 场（热流）驱动墨场，一次噪声采样两层表现：墨晕主层 + 低频读数作热力 tint 层（等温线隐喻）。
2. **渲染**：离屏低清缓冲（约 192px 宽、随视口比例）逐像素 ImageData → `drawImage` 平滑放大铺满；DPR 无关（雾化模糊是特性）；30fps 隔帧 + dt 钳制。
3. **视觉**：墨色读 `--primary-ink`（亮 `#285c82` / 暗 `#a5d8ff`，与衬线标题同色源）；显形阈值 0.60 + 羽化 0.12（约 75–85% 留白保 hero 对比度）；墨 alpha 峰值亮 0.14 / 暗 0.18；热力 tint alpha ≤0.05（暖赭/冷青内置调色板，不新增 CSS token）。
4. **集成**：Teleport body + fixed + `pointer-events:none`；主题双通道实时取色（`data-theme` MutationObserver + 系统深浅 matchMedia）；`prefers-reduced-motion` 单帧静图；`document.hidden` 暂停；resize 防抖重建缓冲。**无指针交互、无滚动视差**——与记账页斥力星空形成「门面静、工作台动」对照；记账页星空零改动（红线）。
5. **降档**：复用 Task 3.3 可复用降档模块（连续 <45fps → 缓冲减半、30→20fps）。

**验证：**
- `cd web && npm run typecheck && npm run test`（Vitest：噪声确定性、阈值/羽化映射、降档策略纯函数）；
- 手测：亮/暗主题、CPU 4x throttle 稳帧、reduced-motion 静帧、移动端；DevTools 抽测 hero 文字对比度 >7:1；console 记账页星空零回归。

**提交：** ① `feat(web): inkField 噪声引擎与墨晕背景组件` ② `feat(web): Landing 接入墨晕热力场，替换静态晨雾光斑`

---

## Wave 4：跨端产品大件

### Task 4.1 预算-挑战-成就联动
**Files:**
- Modify: `app/.../domain/ChallengeEngine.kt`（新增「风险」派生：纯函数，输入 bills 聚合 + 周预算，输出燃烧速度/失败概率档位）
- Modify: 挑战页与预算页 UI（预警条：某挑战依赖的预算烧穿过快 → 橙色提示 + 小盘安慰文案）；`web` 图表页同步展示预警标记
- Test: 风险档位边界用例（正好达标/超支/月末收敛）

**做法：** 全部实时派生，零存储（挑战系统既有原则）；服务端 `ChallengeService.kt` 同步逻辑不受影响。

**验证：** `:app:testDebugUnitTest` + Web Vitest；真机构造快烧预算场景目检。
**提交：** `feat: 预算-挑战联动风险预警（App+Web）`

### Task 4.2 多币种真汇率
**Files:**
- Modify: `server/.../routes/`（新增 `GET /api/rates`）+ 新 `RateService.kt`（每日定时拉公开汇率源缓存 KV，失败回落上次成功值）
- Modify: `app/.../screen/currency/`（DEMO_RATES_VS_CNY 替换为服务端 rates，离线/失败回落演示表）、Web 多币种展示
- Test: 汇率换算与回落逻辑单测

**做法：** 汇率源选免 key 的开放端点（ECB/exchangerate-api 免费档），每日拉一次；`base_currency` 存储与整数分约定不动。

**验证：** 单测 + 断网回落演示表；真机 Web 双端展示一致。
**提交：** `feat: 多币种接入真实汇率（离线回落演示表）`

### Task 4.3 消费地理画像
**Files:**
- Modify: `app/.../screen/map/`（`BillMapScreen`：热力/聚合圆点图层 + 「商圈排行」面板；顺手评估高德 GCJ-02 纠偏 TODO 是否本次做——纠偏公式已知，做则单独提交）
- Test: 网格聚合（经纬度 → 0.01° 网格 → 排行）纯函数单测

**做法：** 仅统计**主动打点**的账单（数据本就稀疏，诚实展示「已打点账单」口径）；排名面板显示 Top 地点 + 累计金额。

**验证：** 单测 + 真机地图目检（含纠偏前后对比，若做）。
**提交：** `feat(app): 消费地理画像——热力与地点排行`

### Task 4.4 邮件账单转发解析
**Files:**
- Modify: `gradle/libs.versions.toml` + `server/build.gradle.kts`（Jakarta Mail）；`server/.../services/` 新 `MailIngestService.kt`（IMAP 轮询 → 发件人白名单 → 解析支付宝/微信邮件账单 → 结构化入账走 `BillService`，原文即弃）
- Modify: `application.conf`（邮箱凭证 env 注入，不入库不进日志）
- Test: 账单邮件 HTML/CSV 样例解析单测（样例文件脱敏后入 `server/src/test/resources/`）

**做法：** 轮询频率 10 分钟；白名单发件人（`@alipay.com` 等可配）；解析失败进日志并 Bot 告警。**隐私自查：邮件正文只在内存解析，不落盘、不送 LLM。**

**验证：** 样例单测 + 真实邮箱端到端一封。
**提交：** `feat(server): 邮件账单转发自动入账`

### Task 4.5 双人/家庭共享账本（立项 → 分期实施）
**里程碑（M1 先出详细独立计划再动工）：**
- **M1 设计与数据模型**：`ledgers` + `ledger_members`（role: owner/member）+ `bills.ledger_id`（Room v18 + Server DDL 同步）；同步协议扩展方案（`server_id` 去重按 ledger 维度）；**产独立计划文档** `docs/superpowers/plans/`。
- **M2 服务端 API**：创建/邀请/加入/退出账本，账单按 ledger 读写与同步；权限校验（非成员不可见）。
- **M3 App UI**：账本切换器 + 邀请码分享 + 成员管理；LWW 冲突提示保留现状。
- **M4 Web + Bot**：Web 账本切换；Bot 查询默认个人账本、`@账本名` 语法查询共享账本。

**做法：** 本计划只锁定里程碑与约束（隐私：成员间互相可见账单属产品预期，需在邀请页明示）；M1 详细计划获批后才写代码。

**验证：** 每里程碑各自三端测试 + 双设备真机同步冒烟。
**提交：** 按里程碑各自提交（首个提交为 `docs: 共享账本M1详细计划`）。

### Task 4.6 浮点金额列退役收官（全计划最后执行）
**前置条件（全部满足才动手）：**
1. Wave 0–4 全部合并并已推生产；
2. 用户所有活跃设备 App 已更新到最新版（旧 App 依赖 API `amount` 兼容字段）；
3. 已备份生产数据（Task 0.8 的备份链路可用且演练过）。

**Files:**
- Modify: Server——API 停发 `amount` 元字段 + `BillsTable.kt` 等删 REAL 列（PG/H2 迁移脚本）；App——Room v18/v19 删浮点列（**SQLite 删列需重建表，严格遵循 v13 的重建顺序教训：先搬 bills 再动 accounts**）；Web——DTO 删 `amount`。
- Test: 三端全量 + 迁移用例（旧 schema → 新 schema 数据等值断言）

**做法：** 分两个提交：① API 停发兼容字段（观察一个周期）；② 数据库列删除。金额读侧三端只走各自 Money 工具的整数分路径。

**验证：** 三端全绿 + 双设备真机同步回归 + 生产冒烟。
**提交：** `refactor: 退役浮点金额兼容字段与列（整数分收官）`

---

## 全局红线与自查清单（每个 Wave 收尾过一遍）

1. **隐私 NFR**：送 LLM 的只有「分类+金额+日期」聚合；新增 Task 1.2/1.4/4.4 的注入与解析内容逐条自查（`InsightService.naturalQueryContext`、`LearningService.processCorrections` 及新入口）。
2. **金额整数分**：任何新金额字段一律 `Long` 分；模板手写 `¥` 处用 `formatMoneyPlain`。
3. **分类种子**：改分类/商家映射 seed 必须与 App `BillRepositoryImpl.seedCategories()` 逐字同序（Task 1.3 商家映射注意）。
4. **Web UI 红线**：仅实施本计划 Wave 3 列出的功能项，不做全局视觉重设计（用户此前两次暂缓）。
5. **性能红线**：Task 2.8 视觉零变化；Task 3.3 星空默认视觉不变（仅性能降档）。
6. **依赖**：新依赖一律先进 `gradle/libs.versions.toml`；顺手收敛 `server/build.gradle.kts` 两处硬编码版本（H2、BouncyCastle）。
7. **同步语义**：涉同步改动三端同时评估（Task 4.5 重点）。

## 每 Wave 验收

- 三端测试命令全绿（见总体策略）；
- `git status --short --branch` 干净，Wave 分支合并 `main` 并推 `origin`；
- 真机/浏览器冒烟按各任务「验证」栏执行，结果记入合并提交说明；
- 更新 `FEATURES.md` 对应模块现状（每 Wave 一次）。
