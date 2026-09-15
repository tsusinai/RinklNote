# RinklNote 记一笔

个人记账应用，双端一体（Android App + Ktor 服务端），全链路支持自然语言与 AI 洞察。
所有 UI 与注释统一使用中文。

## 这是什么

- **客户端**：Android 单 Activity + Jetpack Compose + Material 3，本地 Room 存储，随时离线记账。
- **服务端**：Ktor + Exposed + PostgreSQL（测试用 H2），提供账号、跨端同步、NLU 记账、AI 洞察与 QQ 记账机器人。
- **端到端**：核心记账 | 多端双向同步 | 语音/文本自然语言记账 | 月度总结/异常/习惯提醒 | QQ 微信机器人。

本库是一个 Gradle 多模块 Monorepo：

```
RinklNote/
├─ app/      Android 客户端（Kotlin + Compose + Room）
├─ server/   Ktor 服务端（含 QQ 机器人、LLM 洞察）
└─ docs/     设计/计划/使用文档
```

## 技术栈

| 端 | 技术 |
|---|------|
| App | Kotlin 2.0.21 · Jetpack Compose（BOM 2024.09）· Material 3 · Room 2.6.1 (KSP) · Retrofit/OkHttp · kotlinx-serialization · minSdk 28 / target 36 |
| Server | Ktor Server (Netty) · Exposed · kotlinx-serialization · JWT (HMAC256) · jBCrypt · HikariCP · PostgreSQL / H2 · DeepSeek LLM |
| 测试 | JUnit 4 · AndroidX Test · Espresso · Compose UI Test |

版本号统一在 `gradle/libs.versions.toml`，不要硬编码。

## 客户端 (app)

**架构**：MVVM + Repository，无 DI 框架。`RinklNoteApp.kt` 是服务定位器，lazy 创建数据库/仓库/令牌/设置/网络/同步单例，供各 `ViewModel.Factory` 注入。

- 数据流：`AppDatabase → BillRepository → ViewModel(StateFlow) → Compose(collectAsStateWithLifecycle)`
- 导航：`HorizontalPager` 三页（计划 / 记账 / 资产）+ AI 页，自定义底部导航栏，非 Navigation Compose。
- 事件：每个 ViewModel 定义了 `@Immutable State` + sealed `Event`，通过 `onEvent(...)` 分发；一次性副作用用 `Channel`（如 `QuickAddEffect`）。

**核心页面/流程**
- 记账页：按日分组的「一天一张卡」列表，每张卡含日期头 + 星期 + 当日净额，行内可滑动删除（滑出删除按钮 → 二次确认弹窗）。支持月切换（前后翻月）。
- 快捷记账抽屉（QuickAddDrawer）：自定义数字键盘（无系统 IME）、支出/收入切换、一级分类 + 二级子分类（长按触发）、备注、常用模板；两段式确认可防误触。
- 编辑页（BillEditOverlay）：一级分类为行、点开展开二级子分类，两张独立卡（分类 / 账户）+ 底部键盘，切换收支类型时重置分类并收起二级。
- 计划页：本月预算卡（进度条 / 已花 / 剩余天数 / 超预算提示），点卡片弹出预算键盘。
- 资产页：账户卡片（微信/支付宝/默认），增/改名/改余额/删除。
- AI 页：对话式记账（「午餐28元」）与问账（「上个月交通花了多少」），自动注入本月总结 / 异常提醒 / 习惯提醒；语音输入（SpeechRecognizer）。
- 图表：`ChartBox` 单 Canvas 绘制折线/柱状，`Animatable` 动画，10 天窗口。

**金额与日期**：业务时区统一 `Asia/Shanghai`（`DateUtil.bookkeepingZone()`）。`Bill.date` 只存「当日 0 点」作为天分组键。注意：金额用 `Double`（见「已知债务」）。

**测试**：
```bash
./gradlew test                     # 全部 JVM 单元测试（离线）
./gradlew connectedAndroidTest     # 仪器测试（需设备/模拟器）
./gradlew lint                     # Android Lint
```

## 服务端 (server)

**入口**：`server/src/main/kotlin/com/example/rinklnote/server/ApplicationKt.kt`，Ktor Netty，默认端口 `8080`（`PORT` 可配）。

**配置 / 环境变量**（`Application.kt`、`plugins/Database.kt`）：

| 变量 | 用途 | 默认 |
|------|------|------|
| `PORT` | HTTP 端口 | 8080 |
| `JWT_SECRET` | JWT 签名密钥（强密钥，缺失 fail-fast） | 必填 |
| `JWT_ISSUER` / `JWT_AUDIENCE` | JWT 校验 | rinklnote-server / rinklnote-app |
| `WEBHOOK_SECRET` | QQ Bot 消息验签；Ed25519 公钥 | 强密钥 |
| `DATABASE_URL` | JDBC URL（Postgres；H2 用于测试） | `jdbc:h2:mem:rinklnote` |
| `database.user` / `database.password` | Postgres 凭据 | rinklnote / rinklnote |
| `DEEPSEEK_API_KEY` | LLM 解析/总结 | 必填 |
| `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` | LLM 端点/模型 | api.deepseek.com / deepseek-chat |
| `LLM_TIMEOUT_MS` | LLM 超时 | 10000 |
| `LEARNING_INTERVAL_MIN` | 关键词自学习刷新间隔 | 60 |
| `ANOMALY_THRESHOLD` | 异常支出增幅阈值 | 1.5 |
| `ASR_API_KEY` / `ASR_BASE_URL` / `ASR_MODEL` / `ASR_TIMEOUT_MS` | 语音转文字（可选，未配置则 App 走本地识别） | whisper-1 |

数据库启动时对缺失列/索引自动迁移（`SchemaUtils.createMissingTablesAndColumns` + 迁移脚本），并 `seedIfNeeded` 幂等填充默认分类/账户。

**运行**：
```bash
./gradlew :server:run             # 开发运行（读 env 或 application.conf 兜底）
./gradlew :server:test            # 服务端单元测试
```

**部署（简）**：打 JVM 包后以 systemd 管理，注入上述 env；DB 用 PostgreSQL。详见 `docs/`。

### API 端点

认证（JWT 保护除健康检查外的业务接口）：`/api/auth/*`
- `POST /api/auth/register`、`POST /api/auth/login`
- `GET /api/auth/me`、`POST /api/auth/password`、`GET/PUT /api/auth/ai`（AI 主动推送开关）

账单：`/api/bills/*`
- `POST /api/bills`（上传）、`GET /api/bills/sync`（分页增量拉取）
- `GET/PUT/DELETE /api/bills/{id}`（单条，PUT 为条件更新）、`POST /api/bills/parse`（NLU 解析）、`POST /api/bills/transcribe`（ASR）

账户 / 预算 / 模板：`/api/accounts`、`/api/budgets`、`/api/templates`

洞察（AI）：`/api/insights/*`
- `GET /api/insights/monthly?month=YYYY-MM`（月度总结）
- `GET /api/insights/anomaly`（异常提醒）、`POST /api/insights/query`（自然问账）
- `GET /api/insights/suggest`、`GET/PUT /api/insights/suggest-config`（习惯推荐与配置）
- `GET /api/insights/habit`（习惯提醒）

QQ / 飞书 / 企微机器人：`/api/qq-bot/*`、`/api/feishu-bot/*`、`/api/wecom-bot/*`（三通道同构：status/config/bind/unbind/bind-status 管理面 + 官方回调 webhook）、`/api/qq/webhook/*`（**已废弃**的旧共享密钥协议，仅为未知外部旧客户端保留运行，新接入勿用）。

## LLM / NLU

- **NLU**：规则 + LLM 双引擎。`RuleBasedParser` 兜底本地规则，`DefaultNLUService` 优先用户自定义关键词 > 系统默认 > LLM fallback；`LearningService` 记录修正反馈供自学习。
- **洞察**：`InsightService` 月度总结、异常、自然问账、习惯推荐。自然问账会解析用户提到的月份（`resolveYearMonth`：8月/八月/上个月/去年8月/2026-03），仅把该目标月的聚合（分类+金额+日期 TOP 汇总）喂给 LLM。
- **隐私约束（NFR）**：只给 LLM 送「分类 + 金额 + 日期」聚合，绝不送备注或未聚合明细。

## 数据模型

- **Room（App，version 10）**：`bills`、`categories`、`sub_categories`、`accounts`、`bill_templates`、`budgets`、`chat_messages`，外键 bills→categories/accounts。首次启动 `seedIfNeeded` 填 7 支出 + 4 收入分类（含子分类）与 3 账户。
- **服务端（Exposed）**：`users`、`categories`、`sub_categories`、`accounts`、`bills`、`voice_keywords`、`correction_log`、`bot_config`、`bill_templates`、`budgets`、`webhook_events`、`push_log`。

## 数据同步

- 双向、本地优先，**Last-Writer-Wins** 处理冲突：本地写入标记 `dirty`，编辑/删除为软删除（`deleted=1,dirty=1`）。
- 每行带 `updated_at` / `base_updated_at` / `server_id`：上传时写 `server_id` 去重，拉取用 `updated_at` 增量；条件 PUT 依赖 `base_updated_at` 做乐观锁。
- Android 记账/改单后自动 `pushBill` 尽力推送；服务端增量 `bills/sync` 分页拉取（含编辑与删除墓碑）。

## 文档

- 使用/功能与问答：`docs/使用文档.md`
- 技术 / 数据流 / 细节：`docs/技术文档.md`
- 功能现状与升级规划：`FEATURES.md`
- 设计与计划（superpowers specs/plans）：`docs/superpowers/*`
- 各阶段 PRD / 方案（QQ 机器人、AI 语音、多端同步、QQ openid 开户等）：`docs/阶段*.md`

## 已知债务 / 待办

- 金额用 `Double`，存在浮点漂移与符号判定误差 → 应改整数分或 `BigDecimal`。
- 隐私 NFR 有风险点：`InsightService.naturalQueryContext`、`LearningService.processCorrections` 疑似把未聚合明细/原始 utterance 发给 LLM，应核查并聚合化。
- 服务端 `BillRoutes` 条件 PUT 的乐观锁未并入 UPDATE 的 WHERE，存在并发丢更新；QQ 用户改密码对 `password_hash==null` 会异常。
- `fallbackToDestructiveMigration`（App）→ 应补真实 Migration；`VoiceParser` 语音解析未自动选中分类（老 bug）。
- 详情见全局审查记录与 `FEATURES.md`。
