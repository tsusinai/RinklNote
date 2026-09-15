# AGENTS.md

本文件为 AI 编码代理（Codex / Claude Code / WorkBuddy 等）提供本仓库的工作指引。**动手改代码前请先通读。**

> 最后校正：2026-09-16（相对上版的主要变化：新增省钱挑战 + 成就徽章 + 主题解锁（Room v15 `challenges` 表、三端同步）、bills 增加 lat/lng（Room v16，账单地图真实数据 + 定位聚焦）、当天账单页 `day-detail` 与分享图导出、AI 快捷询问 chips、新账号种子收敛为仅「无账户」账户、WebView 引导深链修正为 `/console/settings`）。

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

> **环境前提（本机 Windows）**：默认 JDK 24 会让 Gradle 8.13 建 Test 任务报 `Type T not present`，且用户路径含撇号（`C:\Users\a'su's`）会破坏 Gradle 缓存。跑任何 gradle 命令前先导出：
> `export JAVA_HOME="D:/Codes/AndroidStudio/jbr" GRADLE_USER_HOME="D:/Codes/RinklNote/.gradle-home"`
> （详见 docs/superpowers/plans/2026-09-09-budget-upgrade.md）

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
| App | Kotlin 2.0.21 · Compose（BOM 2024.09.00）· Material 3 · Room 2.6.1 (KSP) · Retrofit/OkHttp · kotlinx-serialization · DataStore · Haze（毛玻璃）· Coil · Glance（桌面小组件）· osmdroid 6.1.20（地图） |
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
| `ui/` | `component/`（含 `MoreDrawer` 更多抽屉、`RinklTopBar`、`PressScale`）、`screen/{bookkeeping,plan,assets,ai,profile,login,quickadd,currency,importbills,map,search,web,challenge,day}`、`viewmodel/`、`theme/`（含 `RinklColors` 主题令牌）、`util/`（含 `DisplayPreferences`、`LocationGrabber` 一次性定位、`BillImageExporter` 分享图绘制）。注意：`screen/import/` 目录名与包名 `...screen.importbills` **不一致是有意的，别"修"**；搜索 / 导入的 ViewModel 就近放在各自 screen 包（路由级短生命周期），其余 VM 在 `viewmodel/` |
| `sync/` | `SyncManager`（双向同步，Mutex 单飞） |
| `notification/` | `DailyReportReceiver`、`NotificationHelper` |
| `widget/` | `RinklNoteAppWidget`（Glance 桌面小组件） |
| `util/` | `DateUtil`（业务时区）、`Money`（分⇄元换算/格式化）、`ReorderRanks`（拖动排序）、`BillCsvExporter`、`VoiceInputUtil`、`VoiceRecorder` |

**导航（易错点）**：`AppNavigation.kt` 用的是 **Jetpack Navigation Compose（`NavHost`）**，不是 `HorizontalPager`。4 个底部 tab（计划 / 记账 / 资产 / 我的），start destination 是**记账**。flat route 全集：`ai`、`bill-edit`（编辑账单独立页）、`budget-categories`（分类预算）、`budget-edit`、`account-editor/{accountId}`、`month-detail`、`challenges`（省钱挑战，计划 tab 摘要卡进入）、`day-detail/{dayStart}`（当天账单页，Long 参数）、`bill-map`（账单地图，可选 `?focusBillId=` 聚焦指定账单）、`bill-import`（CSV 导入）、`multi-currency`（多币种）、`bill-search`（搜索账单）、`custom-theme`（自定义主题）、`background-crop/{uri}`（背景裁剪）、`web-view?url={url}&title={title}`（内嵌网页，参数 URL 编码）。记账页「更多抽屉」`MoreDrawer` 是 地图 / 导入 / 多币种 / 搜索 的统一入口。页面过渡方向按 **tab 顺序**判定，不能按 push/pop 判（有 `NavigationTransitionDirectionTest` 把关）。`HorizontalPager` 只出现在 `MonthChartPager`（月度明细的三段式图表）。

**关键模式**

- **State + Event**：每个 ViewModel 定义一个 `@Immutable` State data class + sealed Event 接口。
- **一次性副作用**：用 `Channel`（如 `QuickAddEffect`），由 `AppNavigation.kt` 里的 `LaunchedEffect` 消费。
- **快速记账确认是一步制**：数字键盘点确认 → 校验分类/账户/金额 → `finalConfirm()` 直接落库关抽屉（`QuickAddViewModel.confirm()`）。曾经的「两段式确认」已**有意移除**，不要按旧文档给加回来。
- **金额一律整数分**：三端存储与运算都是 `Long` 分（`amountMinor` / `amount_minor` / `amountMinor`）。App 走 `util/Money.kt`（`parseMinor`/`format`/`formatPlain`），服务端走 `services/Money.kt`（`toMinor`/`fromMinor`/`format`/`resolve*Minor`），Web 走 `utils/money.ts`（`formatMoney`/`formatMoneyPlain`/`parseMoneyToMinor`）。**展示契约**：`formatMoney` 输出带 `¥` + 千分位，模板里已手写 `¥` 字面量的地方必须用 `formatMoneyPlain`，否则渲染成「¥¥」。
- **主题令牌**：颜色不直接写死，走 `ui/theme/RinklColors.kt` 的 **10 个槽位**（`RinklThemeSlot`：`FONT / PRIMARY / TOP_BAR / ICON / BORDER` + `HEATMAP / CHART / NAV_ICON / EXPENSE / INCOME`，后五个是 2026-09 个性化偏好新增，EXPENSE/INCOME 即「收支色」），经 `LocalRinklColors` 组合局部注入；自定义色以 `#AARRGGBB` 存 DataStore，是固定色**不再随暗色主题反转**。**BORDER 默认 `Color.Transparent`（无边框默认）**：分割线在透明时回落 `DefaultDividerGray`，`DefaultCardBorder` 不再是卡片默认描边。
- **展示偏好桥**：`ui/util/DisplayPreferences.kt` 是进程级单例，MainActivity 把 DataStore 的 `show_currency_symbol` / `card_overlay` **单向**镜像进内存，供 `Money.format`、Canvas 等**非组合代码同步读取**；改这类偏好键别绕过这条桥。
- **挑战与成就**：`challenges` 表只存「承诺」（type / period_start / goal / status），全部进度由 bills **实时派生**（`domain/ChallengeEngine.kt` + `Achievements.kt`，纯 JVM 函数）；**goal 单位由 type 决定**（无消费日 / 连续记账 = 天数，每周预算 = 整数分，不加 goalUnit 列）。周期结束的 ACHIEVED/MISSED 回写是任意设备派生时懒更新（值确定一致，LWW 无冲突）。成就与主题解锁**零存储**，删账单会实时回退进度——是特性不是 bug。
- **账单位置打点与地图坐标系**：bills 的 `latitude/longitude`（**WGS-84**）只在用户**主动打点**时写入（快速记账「位置」chip / 编辑页位置区，`util/LocationGrabber` 一次性定位），语音 / QQ / AI 来源一律不带；账单地图的高德瓦片是 **GCJ-02**，未做纠偏（大陆视觉偏移数百米，纠偏 TODO 在 `BillMapScreen`）。当天账单页分享图由 `util/BillImageExporter` Canvas 绘制，经 FileProvider（`cacheDir/share/`）出 `content://`。
- **更多抽屉二级页**：搜索账单是**本地查询** —— `BillSearchViewModel`（就在 `screen/search/` 包里）拿 `observeAllBills()` 全量 Room Flow 内存过滤，无服务端搜索，结果行跳 `bill-edit`。多币种页 `base_currency` 存 DataStore（默认 CNY），汇率是 `DEMO_RATES_VS_CNY` **演示表** —— 不动 Room、不动整数分约定，接真汇率时只换表。地图用 osmdroid + `BillMapScreen` 私有 `AmapTileSource`（高德 webrd 瓦片、**无 key**，因 OSM MAPNIK 屏 osmdroid 默认 UA）；**UA 与缓存路径必须在首个 `MapView` 前设置**（缓存在 `context.cacheDir/osmdroid`）；金额直接合成进标记位图，InfoWindow 已移除。内嵌网页一律走 `web-view` 路由（`WebViewScreen`），**别再开外部浏览器 Intent**。
- **图表**：`ChartBox` 用单个 `Canvas` 手绘折线/柱状，无第三方图表库；`Animatable` 用 `snapTo(0f)` → `animateTo(1f)`。**缩放用的 `maxVal` 必须取目标 `expenseData.max()`，不能用动画中的值**，否则动画比例会漂移。

**数据库**：`rinklnote.db`，**Room version 16**，8 张表 —— `bills`、`categories`、`sub_categories`、`accounts`、`bill_templates`、`budgets`、`chat_messages`、`challenges`；bills 外键指向 categories / accounts。`exportSchema = true`，schema 落在 `app/schemas`。首次启动 `seedIfNeeded()` 幂等填充 25 个主分类（含子分类）与 **仅 1 个「无账户」账户**（微信 / 支付宝不再预置，用户自建；v14 起带 `icon_key`）。**`MIGRATION_1_2` … `MIGRATION_15_16` 全链路都在**：v12 给 bills 加 `sort_order`（同日拖动重排，NULL = 按 `COALESCE(sort_order, created_at)` 兜底），v13 重建四张含金额的表把 REAL 换成 INTEGER 分（**重建顺序有讲究**：bills 外键引用 accounts，必须先搬走 bills 数据再重建 accounts，顺序错会撞外键约束），v14 给 accounts 加 `icon_key`，v15 建 `challenges` 表（`server_id` 唯一索引），v16 给 bills 加 `latitude/longitude REAL`。但 `buildDatabase()` 末尾仍挂着 `fallbackToDestructiveMigration()` 兜底 —— 没覆盖到的路径会直接清库。

**金额与时间**：金额存储与传输统一**整数分**（App `amountMinor: Long` / 服务端 `amount_minor BIGINT` / Web `amountMinor`）；API 对旧端保留 `amount`（元，Double）兼容字段，序列化时必须 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`，否则 kotlinx-serialization 会把恒等于默认值的兼容字段整个省略导致旧客户端解析失败。业务时区统一 `Asia/Shanghai`（`DateUtil.bookkeepingZone()`）；`Bill.date` 只存「当日 0 点」作为天分组键。

**语音记账**：Android `SpeechRecognizer`（zh-CN）实时出字，本地 `VoiceParser` 边听边解析出「¥金额 · 分类」预览；服务端 Whisper 兜底（配了 `ASR_API_KEY` 才走）。记账抽屉的语音条每记一笔自动回到聆听，一句可拆多笔。

### Server —— Ktor + Exposed

- `Application.kt` 组装插件与路由；`plugins/` 放 `Database`（含自动迁移 + `seedIfNeeded`）、`Security`（JWT）、`Serialization`、`ErrorHandling`。
- `routes/`：认证、账单、账户、预算、模板、挑战（GET/PUT `/api/challenges`）、洞察、纠正、关键词、AI 助手、ASR 转写、多通道 Bot（QQ / 飞书 / 企微 / 订阅号的 webhook 回调 + `/api/{qq,feishu,wecom}-bot/*` 三通道同构管理面；旧 `/api/qq/webhook` 共享密钥协议已废弃但保留运行）。
- `services/`：`nlu/`（规则 + LLM 双引擎）、`insight/`、`asr/`、QQ Bot（HTTP webhook Ed25519 验签 + WebSocket 网关长连接）、`FeishuBotService` / `WecomBotService` / `MpBotService`（三通道收发与 bot_config KV，配置走 Web 管理页非 env）、`BotCommands`（多通道共享指令常量：推送开关 / 登录码正则 / source 词表）、`wx/WxCryptUtil`（微信系 SHA1 验签 + AES-256-CBC/PKCS7 + 手写 XML，零依赖）、`PushScheduler`（分通道 send，目标通道 飞书>企微>QQ）、`Money`。
- `tables/`：Exposed 表定义（users / bills / budgets / bot_config / push_log …）。`users` 的多通道 bot 身份列（均可空 + 唯一索引）：`qq_openid`、`feishu_open_id`、`wechat_openid`（订阅号）、`wecom_userid`（企业微信）。
- 分类种子：`BillService.seedCategories()` 启动时**无条件幂等回填**；其清单**必须与 App `BillRepositoryImpl.seedCategories()` 逐字同序、只增不改**（id 按列表顺序续编），改分类种子两侧要同步改。默认账户两侧均**仅预置「无账户」**（`ensureDefaultAccounts` / App `seedAccounts` 已同步收敛，勿再预置微信 / 支付宝）。
- 认证：JWT 保护除健康检查外的业务接口；限流见 `InMemoryRateLimiter`。

### Web —— Vue 3 SPA

`src/api`（各域 REST 封装 + `http.ts`）、`src/stores`（Pinia：auth / data / theme）、`src/router`（含 guards）、`src/views/{Landing,Login,console/*,landing/*}`、`src/utils`、`src/components`、`src/styles`。主题令牌是 `src/styles/theme.css` 的 CSS 变量（含 `--on-primary`，图表色已对齐 App 的 PiePalette）；提交信息里的「Web 端令牌同步」指 **CSS 设计令牌**，不是登录态同步，别会错意。**App 内 WebView 深链必须用 `/console/*`**：settings 等是 console 子路由，根路径没有 catch-all，深链打错（如 `/settings`）vue-router 会空渲染 → WebView 白屏（2026-09-16 已修的真实案例）。

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
