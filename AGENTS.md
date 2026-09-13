# AGENTS.md

本文件为 AI 编码代理（Codex / Claude Code / WorkBuddy 等）提供本仓库的工作指引。**动手改代码前请先通读。**

> 最后校正：2026-09-14（相对上版的主要变化：`:server` 重新 include 进 Gradle、金额三端统一为整数分（Room v13）、快速记账两段式确认已改为一步确认、导航新增 bill-edit / month-detail / custom-theme / background-crop 路由、新增主题令牌系统 RinklColors）。

## 项目

**RinklNote 记一笔** —— 个人记账应用，双端一体（Android App + Ktor 服务端 + Web SPA + QQ 机器人），全链路支持自然语言与 AI 洞察。

- **所有 UI 文案与代码注释统一使用中文。**
- 仓库是 **monorepo**，Android（`:app`）与服务端（`:server`）都已 include 进 Gradle；Web（`web/`）走 npm 独立构建。

```
RinklNote/
├─ app/       Android 客户端（Kotlin 2.0.21 + Compose + Room）
├─ server/    Ktor 服务端（Kotlin JVM 17 + Exposed + PostgreSQL/H2）
├─ web/       Web SPA（Vue 3 + Vite + Pinia，npm 独立构建）
├─ docs/      设计与计划（含 superpowers specs/plans、各阶段 PRD）
├─ resource/  图标等静态资源（SVG）
└─ gradle/libs.versions.toml   统一版本目录
```

其它文件：`README.md`（人读的项目总览）、`FEATURES.md`（功能现状与升级规划）、`CLAUDE.md`（指向本文件）。

## 构建与测试

### Android 客户端（`:app`）

```bash
./gradlew assembleDebug            # 构建 debug APK
./gradlew assembleRelease          # 构建 release APK（minify + shrinkResources 已开）
./gradlew installDebug             # 安装到已连接设备
./gradlew test                     # 全部 JVM 单元测试（离线）
./gradlew connectedAndroidTest     # 仪器测试（需设备/模拟器）
./gradlew lint                     # Android Lint

# 跑单个测试类
./gradlew test --tests "com.example.rinklnote.ui.viewmodel.BookkeepingViewModelTest"
```

`namespace` / `applicationId` = `com.example.rinklnote`；compileSdk 36、minSdk 28、targetSdk 36；JVM target 11。App 单测套件当前**全绿**（2026-09 修复完 6 项历史失败），改动后请保持。

### 服务端（`server/`）

```bash
./gradlew :server:run          # 启动 Ktor（Netty，默认 8080，PORT 可配）
./gradlew :server:test         # JVM 单测（H2）
./gradlew :server:test --tests "com.example.rinklnote.server.MoneyTest"   # 跑单个测试类
```

- `settings.gradle.kts` 已 `include(":server")`，可直接用 Gradle 构建（README 若与此冲突，以本文件为准）。
- 模块用 `kotlin("jvm")` + `application` 插件，`mainClass = com.example.rinklnote.server.ApplicationKt`，**JVM target 17**（与 app 的 11 不同）。
- 入口 `Application.kt`，配置读 `src/main/resources/application.conf` + 环境变量；测试用 H2（见 `TestDatabase.kt`），当前 91 项全绿。

### Web（`web/`）

```bash
cd web
npm install
npm run dev         # Vite 开发服务器
npm run build       # vue-tsc --noEmit && vite build
npm run typecheck   # 仅类型检查
npm run test        # vitest run
```

### 版本号规则

所有依赖版本集中在 `gradle/libs.versions.toml`，用 `alias(libs.plugins.*)` / `libs.*` 引用，**不要在 build 文件里硬编码版本或 Maven 坐标**。
（已知违反：`server/build.gradle.kts` 里硬编码了 `com.h2database:h2:2.3.232` 与 `org.bouncycastle:bcprov-jdk18on:1.78`，改到该文件时顺手收敛。）

## 技术栈

| 端 | 技术 |
|---|------|
| App | Kotlin 2.0.21 · Compose（BOM 2024.09.00）· Material 3 · Room 2.6.1 (KSP) · Retrofit/OkHttp · kotlinx-serialization · DataStore · Haze（毛玻璃）· Coil · Glance（桌面小组件） |
| Server | Ktor 2.3.13 (Netty) · Exposed 0.51.1 · HikariCP · PostgreSQL / H2 · java-jwt (HMAC256) · jBCrypt · BouncyCastle (Ed25519) · DeepSeek LLM |
| Web | Vue 3.5 · Vite 6 · Pinia · vue-router 4 · ECharts 5 · TypeScript 5.6 · Vitest |
| 构建 | Gradle 8.13 + Kotlin DSL + version catalog · AGP 8.13.0 |

## 架构

### App —— MVVM + Repository，单 Activity，无 DI 框架

```
RinklNoteApp（服务定位器，手动 DI）
  └─ AppDatabase (Room 单例)
       ├─ BillDao / CategoryDao / AccountDao / BudgetDao / BillTemplateDao / ChatMessageDao
       └─ Repository（Bill / Account / Budget / Chat）
            ├─ StateFlow 缓存（分类、账户，只加载一次）
            └─ Room Flow（响应式，按月观察）
                 ↓
ViewModel（每个都有 @Immutable State + sealed Event，经 onEvent(...) 分发）
  └─ StateFlow<State>
       ↓
Compose UI（collectAsStateWithLifecycle）
```

包结构（`app/src/main/java/com/example/rinklnote/`）：

| 包 | 内容 |
|---|---|
| `data/db/` | `AppDatabase`、`Converters`、`dao/`、`entity/` |
| `data/local/` | `SettingsManager`、`TokenManager`、`TokenCipher`（Keystore 加密） |
| `data/network/` | `ApiService`、`RetrofitClient`、`dto/` |
| `data/repository/` | 仓库接口 + `*Impl` |
| `domain/` | `BillType`、`Source`、`MessageKind`、`MonthlyChart` |
| `navigation/` | `AppNavigation`（NavHost 编排）、`BookingOrchestrator` |
| `ui/` | `component/`、`screen/{bookkeeping,plan,assets,ai,profile,login,quickadd}`、`viewmodel/`、`theme/`（含 `RinklColors` 主题令牌）、`util/` |
| `sync/` | `SyncManager`（双向同步，Mutex 单飞） |
| `notification/` | `DailyReportReceiver`、`NotificationHelper` |
| `widget/` | `RinklNoteAppWidget`（Glance 桌面小组件） |
| `util/` | `DateUtil`（业务时区）、`Money`（分⇄元换算/格式化）、`ReorderRanks`（拖动排序）、`BillCsvExporter`、`VoiceInputUtil`、`VoiceRecorder` |

**导航（易错点）**：`AppNavigation.kt` 用的是 **Jetpack Navigation Compose（`NavHost`）**，不是 `HorizontalPager`。4 个底部 tab（计划 / 记账 / 资产 / 我的），start destination 是**记账**；flat route 还有 记账页下属的 `ai`、`bill-edit`（编辑账单独立页）、`month-detail`、`profile` 下的 `custom-theme`（自定义主题）、`background-crop/{uri}`（背景裁剪）。页面过渡方向按 **tab 顺序**判定，不能按 push/pop 判。`HorizontalPager` 只出现在 `MonthChartPager`（月度明细的三段式图表）。

**关键模式**

- **State + Event**：每个 ViewModel 定义一个 `@Immutable` State data class + sealed Event 接口。
- **一次性副作用**：用 `Channel`（如 `QuickAddEffect`），由 `AppNavigation.kt` 里的 `LaunchedEffect` 消费。
- **快速记账确认是一步制**：数字键盘点确认 → 校验分类/账户/金额 → `finalConfirm()` 直接落库关抽屉（`QuickAddViewModel.confirm()`）。曾经的「两段式确认」已**有意移除**，不要按旧文档给加回来。
- **金额一律整数分**：三端存储与运算都是 `Long` 分（`amountMinor` / `amount_minor` / `amountMinor`）。App 走 `util/Money.kt`（`parseMinor`/`format`/`formatPlain`），服务端走 `services/Money.kt`（`toMinor`/`fromMinor`/`format`/`resolve*Minor`），Web 走 `utils/money.ts`（`formatMoney`/`formatMoneyPlain`/`parseMoneyToMinor`）。**展示契约**：`formatMoney` 输出带 `¥` + 千分位，模板里已手写 `¥` 字面量的地方必须用 `formatMoneyPlain`，否则渲染成「¥¥」。
- **主题令牌**：颜色不直接写死，走 `ui/theme/RinklColors.kt` 的 5 个令牌（`FONT / PRIMARY / TOP_BAR / ICON / BORDER`），卡片描边、分割线统一取 `BORDER`（分割线跟随边框色），经 `LocalRinklColors` 组合局部注入；自定义色以 `#AARRGGBB` 存 DataStore。
- **图表**：`ChartBox` 用单个 `Canvas` 手绘折线/柱状，无第三方图表库；`Animatable` 用 `snapTo(0f)` → `animateTo(1f)`。**缩放用的 `maxVal` 必须取目标 `expenseData.max()`，不能用动画中的值**，否则动画比例会漂移。

**数据库**：`rinklnote.db`，**Room version 13**，7 张表 —— `bills`、`categories`、`sub_categories`、`accounts`、`bill_templates`、`budgets`、`chat_messages`；bills 外键指向 categories / accounts。`exportSchema = true`，schema 落在 `app/schemas`。首次启动 `seedIfNeeded()` 幂等填充 7 支出 + 4 收入分类（含子分类）与 3 账户（微信 / 支付宝 / 默认）。**`MIGRATION_1_2` … `MIGRATION_12_13` 全链路都在**：v12 给 bills 加 `sort_order`（同日拖动重排，NULL = 按 `COALESCE(sort_order, created_at)` 兜底），v13 重建四张含金额的表把 REAL 换成 INTEGER 分（**重建顺序有讲究**：bills 外键引用 accounts，必须先搬走 bills 数据再重建 accounts，顺序错会撞外键约束）。但 `buildDatabase()` 末尾仍挂着 `fallbackToDestructiveMigration()` 兜底 —— 没覆盖到的路径会直接清库。

**金额与时间**：金额存储与传输统一**整数分**（App `amountMinor: Long` / 服务端 `amount_minor BIGINT` / Web `amountMinor`）；API 对旧端保留 `amount`（元，Double）兼容字段，序列化时必须 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`，否则 kotlinx-serialization 会把恒等于默认值的兼容字段整个省略导致旧客户端解析失败。业务时区统一 `Asia/Shanghai`（`DateUtil.bookkeepingZone()`）；`Bill.date` 只存「当日 0 点」作为天分组键。

**语音记账**：Android `SpeechRecognizer`（zh-CN）实时出字，本地 `VoiceParser` 边听边解析出「¥金额 · 分类」预览；服务端 Whisper 兜底（配了 `ASR_API_KEY` 才走）。记账抽屉的语音条每记一笔自动回到聆听，一句可拆多笔。

### Server —— Ktor + Exposed

- `Application.kt` 组装插件与路由；`plugins/` 放 `Database`（含自动迁移 + `seedIfNeeded`）、`Security`（JWT）、`Serialization`、`ErrorHandling`。
- `routes/`：认证、账单、账户、预算、模板、洞察、纠正、关键词、AI 助手、QQ Bot（管理 + Webhook）、ASR 转写。
- `services/`：`nlu/`（规则 + LLM 双引擎）、`insight/`、`asr/`、QQ Bot（HTTP webhook Ed25519 验签 + WebSocket 网关长连接）、`PushScheduler`、`Money`。
- `tables/`：Exposed 表定义（users / bills / budgets / bot_config / push_log …）。
- 认证：JWT 保护除健康检查外的业务接口；限流见 `InMemoryRateLimiter`。

### Web —— Vue 3 SPA

`src/api`（各域 REST 封装 + `http.ts`）、`src/stores`（Pinia：auth / data / theme）、`src/router`（含 guards）、`src/views/{Landing,Login,console/*,landing/*}`、`src/utils`、`src/components`、`src/styles`。

## 硬性约定

1. **隐私 NFR**：送给 LLM 的只能是「分类 + 金额 + 日期」的**聚合**结果，**绝不送备注或未聚合明细**。`InsightService.naturalQueryContext` 与 `LearningService.processCorrections` 是已知风险点，改动这两处务必自查。
2. **金额**：三端一律整数分 `Long`，换算/格式化只走各端 Money 工具（App `util/Money.kt`、Server `services/Money.kt`、Web `utils/money.ts`），**不要再引入 `Double` 金额字段**；旧 `amount`（元）字段仅作 API 兼容保留。
3. **同步语义**：本地优先 + Last-Writer-Wins；编辑 / 删除为软删除（`deleted=1, dirty=1`）；`updated_at` 增量拉取、`server_id` 去重、`base_updated_at` 做条件 PUT 乐观锁。改同步逻辑要同时考虑 App / Web / 服务端三处。
4. **测试**：改 ViewModel / 同步 / NLU / 金额相关逻辑，必须补或跑对应单测（App：JUnit4 + Mockito-Kotlin；Server：JUnit4 + H2；Web：Vitest）。
5. 新增依赖先加到 `gradle/libs.versions.toml`，再在模块里用 `libs.*` 引用。

## 已知债务 / 待办（改动相关区域时留意）

- 金额整数分迁移**收尾未做**：观察 1~2 个发布周期后要删三端的旧浮点列（`amount` REAL 等）与 API 兼容字段；删之前兼容字段必须保持 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`。
- `fallbackToDestructiveMigration` 兜底仍会清库。
- 服务端 `BillRoutes` 条件 PUT 的乐观锁仍是「先读后写」（比较 `baseUpdatedAt` 后 UPDATE 不带版本条件）→ 并发可丢更新。
- QQ 用户改密码时对 `password_hash == null` 会异常。
- 设备时区显示：部分日期仍用 `LocalDate.now()`（系统时区），未统一到业务时区。
- ViewModel Factory 样板重复（可选引入 DI）。
- 完整清单见 `README.md`「已知债务 / 待办」与 `FEATURES.md`。

## 文档索引

| 文档 | 用途 |
|---|---|
| `README.md` | 项目总览、API 端点、环境变量、同步与数据模型 |
| `FEATURES.md` | 各功能模块现状 + 升级规划 |
| `docs/使用文档.md` | 面向使用者的功能说明 |
| `docs/技术文档.md` | 技术细节与数据流 |
| `docs/金额精度迁移方案.md` | Double → 整数分的方案、实施进展与两处关键坑（`@EncodeDefault`、`formatMoney` 契约），动金额前必读 |
| `docs/superpowers/{specs,plans}/` | 规格与实施计划（历史） |
| `docs/阶段*.md` | 各阶段 PRD / 方案 |
