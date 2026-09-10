# 推送链路修复 — 设计文档

> 状态：**待用户确认**（确认后才进实施计划）
> 日期：2026-09-10 · 范围：App 推送 + QQ 端推送（用户已确认「全都修，含 AI 页内注入」）

## 1. 目标

让三个推送面真正生效：

| 面 | 载体 | 期望行为 |
|---|---|---|
| App 系统通知 | `NotificationHelper` + `DailyReportReceiver` | 用户开启后，每天在设定时间收到一条本地日报通知，内容含真实金额与分类 |
| QQ 机器人 | `PushScheduler` | 每天在设定时间推一条日报；月结 / 异常 / 习惯按原设计推送 |
| App AI 页 | `AiViewModel.onEnter` | 进入 AI 页按需注入月结 / 异常 / 习惯气泡（受 `ai_disabled` 门控） |

## 2. 根因（已定位，含证据）

**R1 — QQ：日报分支被嵌进了习惯分支内部** `server/.../services/PushScheduler.kt:85-102`

后加入的 `DAILY_REPORT` 判断块落在 `if (!alreadyPushed(u.id, "HABIT", dayKey)) {` 与
`if (content != null) {` 的**内部**。花括号总数恰好平衡所以能编译，但语义完全错位：

- `habitProvider` 返回 `null`（**常态**：时段未命中 / 今日已记该分类 / suggest 未开启）→ 日报**永不发送**；
- `HABIT` 一旦 `markPushed`，整个块当天被 `if (!alreadyPushed("HABIT"))` 跳过 → 日报当天再无机会。

**R2 — QQ：日报内容是空壳** `server/.../services/insight/InsightService.kt:104-110`

```kotlin
appendLine("支出: ￥")        // 金额未插值
appendLine("  - : ￥")        // 分类名与金额均未插值
billCount = 0                 // 写死
incomeCategories = emptyList()// 写死
```

根因是 `BillService.MonthStats`（`services/BillService.kt:472-476`）**只有**
`totalExpense / totalIncome / topExpenseCategories`，既没有笔数也没有收入分类。

**R3 — App：日报开关 UI 整块缺失**

- `SettingsManager`（`data/local/SettingsManager.kt:66-79`）已实现 `setDailyReportEnabled / setDailyReportTime / setDailyReportQqBot`，但**全项目零调用点**；
- `ui/screen/profile/ProfileScreen.kt:96`、`:99` 声明了 `dailyReportEnabled` / `dailyReportQqBot` 却从未被使用；
- `ProfileScreen.kt:100` 的 `showTimePicker` 从未被置 `true`，`TimePickerDialog`（`:632`）是**死代码**。

→ 用户无法开启 → DataStore 默认 `false` → `RinklNoteApp.kt:89-94` 永不调用 `schedule()` → **通知永不触发**。

**R4 — App：Android 13+ 未申请 `POST_NOTIFICATIONS` 运行时权限**

全项目只有一处权限请求（`AppNavigation.kt:304`，RECORD_AUDIO）。
即使开关打开，`NotificationHelper.kt:32-36` 也会因权限未授予直接 `return`，静默丢弃。

**R5 — App：Android 12+ 闹钟不重复** `notification/DailyReportReceiver.kt:57-72`

API ≥ 31 走 `setExactAndAllowWhileIdle` / `setAndAllowWhileIdle`，**两者都是一次性**，
且没有任何自我重排 → 只响一次。（API < 31 的 `setRepeating` 分支反而正确。）

**R6 — AI 页内注入：未发现结构性缺陷**

`AuthState.aiDisabled` → `AppNavigation.kt:245-249` → `AiViewModel.onEnter(:78)` →
`loadMonthlyIfStale / loadAnomalyIfStale / loadHabitIfStale` 链路完整；
`MessageKind`（`domain/MessageKind.kt`）枚举值与去重键的字符串字面量一致。
其「未生效」属于**下游原因**（服务端未部署新端点 / 未登录 / `ai_disabled=true`）。
→ 本设计只做**运行时验证 + 加固**，不预设要改。

## 3. 设计决策

### D1 QQ：把 DAILY_REPORT 提为平级，并用 helper 消除模板重复

把四段「判重 → 取内容 → 发送 → 标记」抽成一个私有方法：

```kotlin
private suspend fun pushIfNeeded(userId: Long, openid: String, type: String, dayKey: String,
                                 provider: suspend (Long) -> String?) {
    if (alreadyPushed(userId, type, dayKey)) return
    val content = provider(userId) ?: return
    if (send(openid, content, UUID.randomUUID().toString())) {
        markPushed(userId, type, dayKey); log.info("$type 已推送 user=$userId")
    } else log.warn("$type 发送失败 user=$userId")
}
```

`tick()` 主体退化为四行平级调用（MONTHLY_SUMMARY 带窗口条件）。**这是纵深防御**：
扁平结构从根上杜绝再次出现"新分支嵌进旧分支"的错位。

### D2 QQ：日报内容补全

扩展 `BillService.MonthStats` 增加两个字段（**带默认值**，不破坏既有调用点）：

```kotlin
data class MonthStats(
    val totalExpense: Double,
    val totalIncome: Double,
    val topExpenseCategories: List<Pair<String, Double>>,
    val topIncomeCategories: List<Pair<String, Double>> = emptyList(),
    val billCount: Long = 0
)
```

`monthlyStats` 的 SQL 增加 `COUNT(*)` 与收入侧 `GROUP BY`。
`InsightService.dailyReport` 改为输出：

```
✅ 今日账单总结
支出: ￥128.50
收入: ￥3000.00
共 5 笔
支出分类:
  - 三餐: ￥48.00
  - 交通: ￥18.50
```

### D3 QQ：日报时间跟随 App 设置（用户已选）

`users` 表新增三列（与既有 `ai_disabled` 同风格）：

| 列 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `daily_report_enabled` | BOOLEAN | false | 该用户是否要 QQ 日报 |
| `daily_report_hour` | INT | 9 | 发送小时（Asia/Shanghai） |
| `daily_report_minute` | INT | 0 | 发送分钟 |

- `plugins/Database.kt` 的 `runMigrations` 加三条 `ALTER TABLE users ADD COLUMN ...`（幂等，与既有做法一致）。
- 语义分层：
  - `ai_disabled` = **总闸**（关掉全部主动推送，含日报）——已有；
  - `daily_report_enabled` = 日报**子开关**；
  - `daily_report_hour/minute` = 日报时间。
- `PushScheduler` 判定从 `hour >= 9` 改为 `nowMinutes >= 用户设定时刻` 且当日未推
  （**用"≥"而非"=="**：30s tick 不可能精确命中某一分钟，必须用"到点之后当天首推"）。

### D4 App：通知权限 + 闹钟自愈

- `POST_NOTIFICATIONS`：在「我的」页打开日报开关时请求；拒绝则开关回滚 + Toast 引导去系统设置。
- `schedule()`：抽出 `nextTriggerMillis()`，并在 `DailyReportReceiver.onReceive` 末尾**重新 schedule 次日**。
  `canScheduleExactAlarms()` 为 false 时用 `setAndAllowWhileIdle`（近似），仍保证次日重排。
- 开关变更时立即 `schedule()` / `cancel()`。

### D5 App：补回「日报通知」设置卡片（`ProfileScreen`）

放在「我的」页，三行：

1. `日报通知` Switch → `setDailyReportEnabled` + `schedule()/cancel()`
2. `通知时间 09:00` 行 → 点击弹已有的 `TimePickerDialog` → `setDailyReportTime`
3. `QQ 日报` Switch → `setDailyReportQqBot` + 同步 `PUT /api/auth/daily-report`

### D6 AI 页内注入

**不改代码**，只加验证清单（见 §6）。

## 4. 契约变更

服务端：

```
GET  /api/auth/daily-report  -> { enabled, hour, minute }
PUT  /api/auth/daily-report  <- { enabled, hour, minute }
MeResponse 增 dailyReportEnabled / dailyReportHour / dailyReportMinute
```

App：`ApiService.getDailyReportSetting()` / `setDailyReportSetting(...)`；DTO 两份同步定义。

**注意**：App 已有 `ApiService.getDailyReport(): DailyReportResponse`（拉取日报内容），
与新增的设置端点**命名冲突风险** → 新端点命名为 `daily-report-setting`，避免歧义。

## 5. 非目标

- 不新增 WorkManager 后台同步、不改同步协议。
- 不动月结 / 异常 / 习惯的触发窗口（已符合原设计）。
- 不为 App 通知引入远程推送（FCM 等），仍是本地通知。

## 6. 验证策略（含本会话环境限制）

⚠️ **本会话无法执行任何外部程序**（Bash 不可用；PowerShell 里 `hostname.exe` / `node.exe` /
`gradlew.bat` / `git` 全部无输出无退出码，PATH 中 java/gradle/node/git/cmd 均不可见）。
**因此 TDD 的 RED/GREEN 我无法自己跑，也无法用 git worktree / 提交。**

三条路，请选一条：

- **A（推荐）**：你先按前面说的重启 WorkBuddy 让 Bash 恢复（或注销 Windows），之后由我跑
  `./gradlew :app:testDebugUnitTest :server:test` 拿到真实红绿 —— 符合完整 TDD 纪律。
- **B**：你自己在 Android Studio / 终端跑，我把命令和期望输出逐条写进计划。
- **C**：只做静态审查，我交付代码 + 测试，不声明"已验证"。

验证清单（无论哪条路都要覆盖）：

1. 单测：`PushSchedulerTest` 加「habit 为 null 时日报仍发送」「日报当日只发一次」；
   `InsightServiceTest` 加「dailyReport 含金额、分类、笔数」。
2. 冒烟：`GET /api/auth/daily-report` 无 token → 401；有 token → `{enabled:false,hour:9,minute:0}`；
   `PUT {enabled:true,hour:9,minute:0}` 后 GET 回读一致。
3. App：开启日报开关 → 出现权限弹窗 → 允许 → 改时间 → 把时间设到 1 分钟后，
   确认收到通知且内容有金额（**关键：验证 R3/R4/R5 三条同时闭环**）。
4. 隔天再验一次，确认 R5 的"只响一次"已修（不能只测当天）。
5. AI 页：登录 + 未关 `ai_disabled` → 进入 AI 页应出现月结/异常/习惯气泡（受服务端数据条件约束）。

## 7. 风险

- `MonthStats` 加字段会影响 `monthlySummary` / `naturalQuery` 等调用点 —— 用默认值规避编译影响，
  但仍需回归服务端全部既有测试。
- 服务端 `users` 加列依赖 `runMigrations` 的 ALTER 生效；H2 测试库走 `SchemaUtils` 建表，两条路都要覆盖。
- QQ 主动推送需要 Bot 网关在线，无法在本地单测里端到端验证，只能验到"调用了 send"这一层。
