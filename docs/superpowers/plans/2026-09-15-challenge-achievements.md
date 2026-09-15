# 省钱挑战 + 成就系统 实施计划（2026-09-15）

> 源计划：用户桌面 `Plan.md`（产品决策已确认）。本文件是实施契约：**跨文件/跨端的数据结构与签名以下文「契约」小节为准**，各任务照既有代码风格镜像实现。
> 分支：`goal/challenge-achievements`（基于 main @ e590e23）。工作区 `AGENTS.md`、`SearchBillsScreen.kt` 有未提交改动（用户所有），**不提交、不还原、不触碰**。
> 执行模式：Subagent-Driven，A/B/C 三线**并行**（文件不相交），D/E 依赖 A/B 完成后再并行，构建与测试由主会话串行执行（subagent 一律**不跑 Gradle**，避免并发 daemon 冲突）。

## 契约（所有 agent 必须逐字对齐）

### DailySpendStat（Agent A 创建，Agent B/E 按此引用）

```kotlin
package com.example.rinklnote.data.db.entity

data class DailySpendStat(
    val dayStart: Long,      // 当日 0 点 epoch millis（= bills.date 分组键）
    val billCount: Int,      // 当日记账笔数（含收入）
    val expenseMinor: Long,  // 当日支出合计（整数分）
)
```

### BillDao 新增三查询（Agent A；SQL 列名以 entity/Bill.kt 实际字段为准，暴露签名不变）

```kotlin
// 一天一行驱动全部派生；观察窗口由调用方传（挑战页传 今天-400天 ~ 明天，窗口局限见注释）
fun observeDailySpendStats(start: Long, end: Long): Flow<List<DailySpendStat>>   // GROUP BY date，支出 SUM(CASE WHEN 支出类型)
fun countRecordedDays(): Int          // 全时累计记账天数（COUNT DISTINCT date，deleted=0）
fun getFirstBillDate(): Long?         // MIN(date)
```

### Challenge 实体（Agent A，表名 `challenges`，逐列镜像 entity/Budget.kt 的同步字段风格）

```kotlin
// 字段：id(自增PK) serverId:Long? type:String periodStart:Long goal:Long status:String(默认"ACTIVE")
//       updatedAt:Long? deleted:Boolean dirty:Boolean；server_id 唯一索引
// type ∈ NO_SPEND_DAY | BOOKKEEPING_STREAK | WEEKLY_BUDGET（TEXT 存储常量对象，镜像 BillType 写法）
// status ∈ ACTIVE | ACHIEVED | MISSED（@ColumnInfo defaultValue = "ACTIVE"）
// goal 单位由 type 决定：NO_SPEND_DAY/BOOKKEEPING_STREAK=天数，WEEKLY_BUDGET=整数分（双侧注释写明，不加 goalUnit 列）
// periodStart：月挑战=当月1日0点；周挑战=周一0点；连续记账=承诺日0点（Asia/Shanghai）
```

### ChallengeDao（Agent A，方法集镜像 dao/BudgetDao.kt）

`upsert(row)` / `observeAll(): Flow<List<Challenge>>` / `getByScope(type, periodStart)` / `getUnsynced()` / `getByServerId(serverId)` / `updateServerId(id, serverId, updatedAt)` / 软删除与 status/goal 定向更新。

### ChallengeRepository / Impl（Agent A，镜像 BudgetRepository 风格，纯转发 DAO；不写 RinklNoteApp 装配——那是任务 D）

方法：`observeAll(): Flow<List<Challenge>>`、`upsertActive(type, periodStart, goal)`（scope 唯一，存在则改 goal/激活，create-or-update）、`updateGoal(id, goal)`、`updateStatus(id, status)`（均置 dirty=1、updatedAt=now，交 SyncManager 推送）。

### ChallengeEngine（Agent B，`domain/ChallengeEngine.kt`，纯 JVM 函数、无 Android 导入；时区参数默认 `DateUtil.bookkeepingZone()`）

```kotlin
enum class DayKind { NO_RECORD, NO_SPEND, SPEND }   // NO_SPEND = billCount>0 && expenseMinor==0L
// dayKind(stat?) / noSpendDaysBetween(stats, startEpoch, endEpoch): Int
// currentBookkeepingStreak(stats, today: LocalDate): Int   // 今天没记按昨天活着算；只统计 NO_RECORD 之外的天
// longestBookkeepingStreak(stats): Int
// expenseBetween(stats, startEpoch, endEpoch): Long
// weekStartOf(date: LocalDate): LocalDate（周一）→ atStartOfDay(zone) 转 epoch
// monthStartOf / monthEndExclusive（月挑战 periodStart 口径）
// remainingDays(periodEndExclusive, today): Int
// forecastMonth(totalSpentMinor, dayOfMonth, daysInMonth): Long（按日均外推）
// lessBuySaving(categoryTotalMinor, percent: Int): Long（percent 10..100）
// monthlyBudgetOutcomes(stats, budgets): List<MonthBudgetOutcome>  // data class MonthBudgetOutcome(monthStart: Long, budgetMinor: Long?, expenseMinor: Long) 供成就评估
```

### Achievements（Agent B，`domain/Achievements.kt`，纯 JVM；徽章 id 与 `resource/challenge/badge-*.svg` 逐字对应，共 15 枚）

```kotlin
data class AchievementState(val id: String, val unlocked: Boolean, val progressCurrent: Long, val progressTarget: Long)
data class AchievementInput(
    val recordedDays: Int, val firstBillDate: Long?,
    val dailyStats: List<DailySpendStat>,
    val budgetOutcomes: List<MonthBudgetOutcome>,
    val achievedChallengeCount: Int,   // 挑战表 status=ACHIEVED 总数
)
fun evaluateAchievements(input: AchievementInput): List<AchievementState>
```

15 枚口径（防刷：全部从账单确定性推导，删单实时回退）：`record-first`（首笔账）、`record-7/30/100/365`（累计记账天数）、`nospend-month-3/8/15`（单月无消费天数）、`nospend-total-30/100`（累计无消费天数）、`budget-first`（设置过首笔预算）、`budget-month`（任一整月不超支）、`budget-3months`（连续 3 个月不超支）、`challenge-3/10`（完成挑战次数）。连续记账 7/30/100 不单设徽章（无对应资产，由挑战系统承载）；「晨曦/薄荷/琥珀」主题解锁谓词=连续记账30天 / 累计无消费100天 / 连续3月不超支，直接用引擎值判断。

### 服务端（Agent C，逐文件镜像 BudgetsTable / BudgetService / BudgetRoutes 的结构与风格）

- `tables/ChallengesTable.kt`：`challenges` —— id PK、user_id（FK→users，镜像 BudgetsTable 写法）、type varchar(32)、period_start bigint、goal bigint、status varchar(16) default 'ACTIVE'、created_at、updated_at、deleted。**不加任何 Double 金额旧字段**。
- `services/ChallengeService.kt`：`list(userId)` + `upsert(userId, type, periodStart, goal, status)`——scope 唯一键 = user_id+type+period_start，存在则按 LWW 更新（updated_at 新者胜）并复活 deleted=false，否则创建。
- `routes/ChallengeRoutes.kt`：`GET /api/challenges`（列表）+ `PUT /api/challenges`（upsert，JWT 保护，镜像 BudgetRoutes 的鉴权/异常处理）。
- `Application.kt` 注册路由；`plugins/Database.kt` 的 `createMissingTablesAndColumns(...)` 加 `ChallengesTable`。
- DTO（**JSON 契约，Agent D 同步对齐**）：字段名与时间表示**完全镜像现有 BudgetDTO/App ApiService 中 budget 的风格**（读取 `data/network/dto/BudgetDTO.kt` 与服务端 BudgetRoutes 的序列化写法后同风格实现），业务字段为 `type / periodStart / goal / status`，附加同步字段与 budget 一致；无 Double 兼容字段。

### 同步（Agent D，镜像 SyncManager.syncBudgets 全套路数）

`ApiService.getChallenges() / upsertChallenge(body)`；`dto/ChallengeDTO.kt`；`SyncManager` 构造参数加 `challengeDao: ChallengeDao? = null`（默认 null 向后兼容现有测试），`sync()` 末尾 `syncChallenges()`（push getUnsynced → pull 全量 LWW 按 updatedAt，服务端 deleted=1 清本地行），新增非阻塞 `pushChallenge(challenge)`；`RinklNoteApp` 装配 ChallengeRepository 单例并把 dao 传入 SyncManager。测试 `sync/SyncManagerChallengesTest.kt`（Mockito：LWW 合并、dirty 行不被覆盖、deleted 清理，镜像 SyncManagerTest 风格）。

## 任务划分与文件所有权（并行安全边界）

| 任务 | 独占文件 | 产出 |
|---|---|---|
| **A 数据层** | `data/db/entity/Challenge.kt`、`entity/DailySpendStat.kt`、`dao/ChallengeDao.kt`、`dao/BillDao.kt`（追加）、`data/db/AppDatabase.kt`（v15 + MIGRATION_14_15）、`data/repository/ChallengeRepository*.kt` | 实体/DAO/迁移/仓库 |
| **B 引擎** | `domain/ChallengeEngine.kt`、`domain/Achievements.kt`、`app/src/test/.../domain/{ChallengeEngineTest,AchievementsTest}.kt` | 纯函数派生引擎 + 测试 |
| **C 服务端** | `server/**/tables/ChallengesTable.kt`、`services/ChallengeService.kt`、`routes/ChallengeRoutes.kt`、`Application.kt`、`plugins/Database.kt`、`server/src/test/.../ChallengeServiceTest.kt` | 三端之一：API |
| **D 同步** | `data/network/dto/ChallengeDTO.kt`、`data/network/ApiService.kt`（追加）、`sync/SyncManager.kt`、`RinklNoteApp.kt`、`app/src/test/.../sync/SyncManagerChallengesTest.kt` | 三端同步 |
| **E1 挑战页** | `ui/screen/challenge/{ChallengeViewModel,ChallengeScreen}.kt` | VM + 页面 |
| **E2 接线** | `ui/screen/plan/PlanScreen.kt`、`navigation/AppNavigation.kt`、`ui/screen/profile/CustomThemeScreen.kt`、`ui/theme/*`、`res/drawable/`（SVG→VectorDrawable） | 入口/路由/主题解锁/图标资产 |
| **F 收尾** | `AGENTS.md`（增量合并）、`FEATURES.md` | 文档 + 全量验证（主会话） |

## 各任务要点

- **A**：`MIGRATION_14_15` = `CREATE TABLE IF NOT EXISTS challenges(...)` + 唯一索引，逐列对齐 v13 重建 budgets 的写法；`AppDatabase` version 13→…→15 链路补 14→15（现版本 14，含 MIGRATION_13_14 icon_key）。Room schema 15.json 随构建生成后需提交（主会话构建时确认）。
- **B**：时间一律 `LocalDate.now(zone)` / `atStartOfDay(zone)`，禁止裸 `LocalDate.now()`；测试覆盖 23:59 跨天边界、断签、周一起点、预测数学、成就阈值。无 Android 依赖。
- **C**：`ChallengeServiceTest`（H2，镜像现有 *ServiceTest）：scope 唯一、userId 隔离、LWW、deleted 复活。
- **E1**：VM init `ensureCurrentPeriodRows()` 幂等补当月/当周行（月挑战 goal 默认 8 天、周挑战默认=月总预算×7÷当月天数向上取整、无预算则 0 引导手输）；combine(日统计, 挑战, 预算) 单一 State；周期结束/断签懒回写 status（经 repository，自动 dirty → push）；事件=调目标（天数步进/档位/NumericKeypad）。UI：预测卡（含少买滑杆 10%~100%）→ 三挑战卡（无消费日卡内嵌月历打卡墙：绿=无消费/主题色=有支出/灰=未记账、今天描边；复用现有日热力图组件骨架，grep 定位真实组件名）→ 成就墙 15 格（锁定态进度 x/y）→ 主题解锁行。视觉规范：15dp 卡片、46dp 顶栏、字阶 ≥12sp、`rinkShadow` + 毛玻璃、全中文。
- **E2**：PlanScreen LazyColumn 首位「省钱挑战」摘要卡（三行迷你进度 + 预测一句，`onOpenChallenges` 回调）；AppNavigation 加 `challenges` route（plan tab 下属，无序冲突走默认转场）；CustomThemeScreen 解锁预设「晨曦/薄荷/琥珀」（锁定行：锁图标 + 条件文案 + 未解锁点击 Toast，解锁后走现有 `setCustomThemeColor` 一键应用）；徽章资产：把 resource/challenge 的 badge-*.svg / icon-challenge-*.svg 手工转成 `res/drawable/*.xml` VectorDrawable（viewBox→viewport、d→pathData、fill→fillColor；转换不了的特性降级为组合图标）。resource/challenge 下的 `preview.html` 是设计稿，先看它对齐视觉。
- **F**（主会话）：`./gradlew test` + `:server:test` + `assembleDebug` 全绿；AGENTS.md 增量合并（Room v15、challenges route、包表、测试计数）；FEATURES.md 新节；输出真机验收清单；提交（**不含 SearchBillsScreen.kt**）。

## 验收（无模拟器）

JVM：ChallengeEngineTest、AchievementsTest、SyncManagerChallengesTest、ChallengeServiceTest + 现有套件全绿。构建：assembleDebug 成功。真机清单（用户执行）：挑战创建/调目标 → 打卡墙着色 → 成就解锁弹庆祝 → 主题解锁应用 → App/Web/QQ 断网重连同步一轮。
