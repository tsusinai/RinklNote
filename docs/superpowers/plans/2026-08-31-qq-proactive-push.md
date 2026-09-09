# QQ 机器人主动推送 + AI 开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 QQ 机器人实现主动推送（FR4 月结卡片 / FR5 异常提醒 / FR6 习惯提醒），修 `naturalQuery` 的备注泄露点（NFR1），并加一个「关闭 AI 主动推送」用户开关（`UsersTable.ai_disabled`），放到服务端定时任务、App 与 Web 三端。

**Architecture:** 服务端新增 `PushScheduler`（进程内协程 + `delay` 循环，不做 Quartz/外部 cron）。每 tick 按「日历窗口 + `push_log` 表去重」判定三类推送：月结 = 本月最后一天 ≥20:00 且本月有 ≥1 笔且本月未推；异常 = `anomalyCheck` 有 alert 且当天未推；习惯 = `suggestDailyPattern` 命中且今天该分类尚未记且当天未推。内容用纯文本多行。`UsersTable` 加 `ai_disabled` 列；`ai_disabled=true` 时该用户所有主动推送跳过（Q10=A：主动问账/AI 页注入仍走 LLM）。`naturalQuery` 不再把备注送 LLM。

**Tech Stack:** Ktor + Exposed + H2 (server)；Vanilla JS SPA (Web)；Android Kotlin/Compose + Retrofit (App)。测试命令 `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test`。

## Global Constraints

- 时区：一律 `ZoneId.of("Asia/Shanghai")`（用现有 `bookkeepingZone()` 精神，服务端无集中工具，本计划内联常量 `private val SHANGHAI = ZoneId.of("Asia/Shanghai")`）。
- 推送消息形态：**纯文本多行**，不做 QQ 富媒体卡片。
- `ai_disabled` 只影响「主动推送调度」，不影响主动问账 / AI 页注入（Q10=A）。
- 去重：`push_log` 表 `(user_id, type, day_key)` 唯一；月结 `day_key="yyyy-MM"`，异常/习惯 `day_key="yyyy-MM-dd"`。
- 测试：服务端新测试用 `TestDatabase.connect(prefix)`；用项目单测命令跑。
- 提交：按路径显式暂存源码，不用 `add -A`，避免 server/build、app/build 产物入库。

---

## File Structure

### 服务端 (server/src/main/kotlin/com/example/rinklnote/server)
- `tables/UsersTable.kt` — 加 `ai_disabled` 列。
- `tables/PushLogTable.kt`（新建）— 推送去重表。
- `services/UserService.kt` — `UserInfo` 加 `aiDisabled`；加 `findAllBoundQq()`、`setAiDisabled()`、`isAiDisabled()`；`findById/findByPhone/findByQQ/findByQqOpenid` 映射补 `aiDisabled`。
- `services/insight/InsightService.kt` — `naturalQuery` 去备注（抽 `naturalQueryContext` 纯函数便于测试）；加 `habitReminder(userId, now)` 与 `polishHabitCopy(...)`。
- `services/PushScheduler.kt`（新建）— 主调度循环。
- `routes/AuthRoutes.kt` — `GET /api/auth/ai`、`PUT /api/auth/ai`；`/me` 返回 `aiDisabled`。
- `plugins/Database.kt` — 把 `PushLogTable` 加入建表 + 唯一索引迁移。
- `Application.kt` — 构造 `PushScheduler` 并 `start(appScope)` 挂到 appScope。

### App (app/src/main/java/com/example/rinklnote)
- `data/network/dto/DTOs.kt` — `MeResponse` 加 `aiDisabled`；加 `AiDisabledRequest`。
- `data/network/ApiService.kt` — 加 `setAiDisabled`。
- `ui/viewmodel/AuthViewModel.kt` — `AuthState` 加 `aiDisabled`；`fetchProfile` 读取；加 `AuthEvent.SetAiDisabled`。
- `ui/screen/profile/ProfileScreen.kt` — 加「关闭 AI 主动推送」开关行。

### Web (web/index.html)
- `renderSettings` — 加「AI 推送」开关卡片（GET/PUT `/api/auth/ai`）。

### 测试
- 服务端：`services/insight/InsightServiceTest.kt`（新建，context 脱敏 + habitReminder）、`services/PushSchedulerTest.kt`（新建，三类推送 + 去重 + ai_disabled 跳过）、`services/UserServiceTest.kt`（新建或并入，ai_disabled 读写）。

---

## Task 1: 服务端 InsightService 脱敏 + 习惯提醒（纯函数可测）

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/insight/InsightServiceTest.kt`（新建）

**Interfaces:**
- Produces: `InsightService.naturalQueryContext(query: String, categories: List<String>, now: LocalDate, totalExpense: Double, totalIncome: Double, topCategories: List<Pair<String,Double>>, recentBills: List<BillDTO>): String`；`InsightService.habitReminder(userId: Long, now: ZonedDateTime): HabitReminder?`；`InsightService.polishHabitCopy(habit: HabitReminder): String`；`data class HabitReminder(label: String, categoryName: String, amount: Double)`。
- Consumes: 现有 `billService.allBills`, `llmParser.chat`, `loadSuggestConfig`.

- [ ] **Step 1: 写失败测试 — naturalQuery context 不含备注**

在 `server/src/test/kotlin/com/example/rinklnote/server/services/insight/InsightServiceTest.kt`（需先建目录）写：

```kotlin
package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillDTO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightServiceTest {
    @Test
    fun `naturalQueryContext includes category+amount+date but not remark`() {
        val ctx = InsightService.naturalQueryContext(
            query = "最近花了多少",
            categories = listOf("三餐", "交通"),
            now = java.time.LocalDate.of(2026, 8, 20),
            totalExpense = 100.0,
            totalIncome = 0.0,
            topCategories = listOf("三餐" to 100.0),
            recentBills = listOf(
                BillDTO(id = 1, amount = 28.0, billType = "EXPENSE", categoryId = 1,
                    categoryName = "三餐", subCategoryName = null, accountId = 1,
                    remark = "机密周二午餐", date = 1724169600000L, source = "app",
                    createdAt = 1724169600000L)
            )
        )
        assertTrue(ctx.contains("三餐"))
        assertTrue(ctx.contains("28.00"))
        assertFalse("备注不应送 LLM", ctx.contains("机密"))
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.insight.InsightServiceTest"`
Expected: FAIL — `naturalQueryContext` 未定义。

- [ ] **Step 3: 实现 naturalQueryContext 纯函数 + 改造 naturalQuery**

在 `InsightService` 里新增伴随/实例纯函数（`object`/`companion` 均可，测试可直接调；若用实例函数测试可 `InsightService` 无参构造——但它要 `llmParser`/`billService`。故**用 companion/独立顶层的纯函数**，测试零依赖）：

```kotlin
companion object {
    val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

    fun naturalQueryContext(
        query: String,
        categories: List<String>,
        now: LocalDate,
        totalExpense: Double,
        totalIncome: Double,
        topCategories: List<Pair<String, Double>>,
        recentBills: List<BillDTO>
    ): String {
        val recentLines = recentBills.sortedByDescending { it.date }.take(10)
            .joinToString("\n") { "- ${formatDate(it.date)} ${it.categoryName} ¥${"%.2f".format(it.amount)}" }
        return """
用户问题: "$query"

可用分类: ${categories.joinToString("、")}

数据:
- 当前月份: ${now.year}-${now.monthValue}
- 当月总支出: ¥${"%.2f".format(totalExpense)}
- 上月总支出: ¥${"%.2f".format(totalIncome)}
- 当月消费TOP5: ${topCategories.joinToString { "${it.key} ¥${"%.2f".format(it.value)}" }}

最近10笔记录:
$recentLines

请仅基于以上真实数据回答用户的问题，不要编造数据。
如果是金额类问题，直接基于数据计算；如果数据不足以回答，明确告诉用户。
返回JSON: {"answer": "你的回答"}
""".trimIndent()
    }

    /** 格式化为 "M/d"，无备注。 */
    private fun formatDate(epochMs: Long): String {
        val d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), SHANGHAI)
        return "${d.monthValue}/${d.dayOfMonth}"
    }
}
```

然后改 `naturalQuery`（第 118-161 行）为调用该纯函数：把原来的 `shanghai/now/monthStart/monthBills/totalExpense/lastMonthStart/totalIncome/topCategories/recentBills/context` 计算替换为：

```kotlin
suspend fun naturalQuery(userId: Long, query: String): QueryResponse {
    val bills = billService.allBills(userId)
    val categories = transaction { billService.getCategories().map { it.name } }
    val now = LocalDate.now(SHANGHAI)

    val monthStart = now.withDayOfMonth(1).atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
    val monthBills = bills.filter { it.date >= monthStart }
    val totalExpense = Money.cents(monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount })
    val lastMonthStart = now.minusMonths(1).withDayOfMonth(1).atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
    val lastMonthExpense = Money.cents(bills.filter { it.date in lastMonthStart until monthStart && it.billType == "EXPENSE" }.sumOf { it.amount })
    val topCategories = monthBills.filter { it.billType == "EXPENSE" }
        .groupBy { it.categoryName }
        .mapValues { Money.cents(it.value.sumOf { b -> b.amount }) }
        .entries.sortedByDescending { it.value }.take(5)

    // 只送「分类+金额+日期」聚合，移除备注与未聚合明细（NFR1）。
    val context = naturalQueryContext(
        query = query,
        categories = categories,
        now = now,
        totalExpense = totalExpense,
        totalIncome = lastMonthExpense,
        topCategories = topCategories,
        recentBills = bills.sortedByDescending { it.date }.take(10)
    )
    // ... 以下 try/catch 与返回逻辑不变，仍用 llmParser.chat(...) 调 LLM
}
```

注意：原第 141-142 行 `recentBills` 变量与 `formatDate`/`it.remark` 一并移除；`formatDate` 移到 companion（删除原私有 `formatDate`，第 178-181 行）。保持 `com.example.rinklnote.server.services.Money` import 已存在。

- [ ] **Step 4: 跑测试验证通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.insight.InsightServiceTest"`
Expected: PASS。

- [ ] **Step 5: 失败测试 — habitReminder（时段命中 + 今日未记 + 未达阈值）**

把 `InsightServiceTest` 补上（需在测试类里用 `TestDatabase` + 真实 `InsightService`）：

```kotlin
@Test
fun `habitReminder null when today already recorded that category`() {
    // 通过 TestDatabase + insertUser + 插入账单（今天已有一笔三餐）断言返回 null
}
@Test
fun `habitReminder returns reminder when today not recorded`() {
    // 7 天内有三笔三餐28，今天无三餐 → 返回 categoryName=三餐 amount=28.0
}
@Test
fun `habitReminder null when below minOccurrences`() {
    // 只有两次三餐28 → null
}
```

实现样式（先写测试，用 `<init>` 依赖；若不想引入 billService/llmParser 真依赖，则给 `habitReminder` 加可选 `now` 时机参数并让测试用真实 billService —— 见 Task 1 Step 6）：

- [ ] **Step 6: 实现 habitReminder**

在 `InsightService` 加：

```kotlin
data class HabitReminder(val label: String, val categoryName: String, val amount: Double)

fun habitReminder(userId: Long, now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): HabitReminder? {
    val config = loadSuggestConfig()
    if (!config.enabled) return null
    val hour = now.hour
    val window = config.timeWindows.find { hour in it.startHour until it.endHour } ?: return null

    val todayStart = now.toLocalDate().atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
    val bills = billService.allBills(userId)

    // 今天已记该分类 → 不提醒（bills 只有日期粒度，用“今天该分类”近似“该时段未记”）
    if (bills.any { it.billType == "EXPENSE" && it.date >= todayStart && it.categoryName == run { /* 候选分类见下 */ } }) {
    }
    // 实际实现：先算 best，再用 best.categoryName 校验今日未记
    val lookbackStart = now.toLocalDate().minusDays(config.lookbackDays.toLong())
        .atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
    val recent = bills.filter { it.billType == "EXPENSE" && it.date >= lookbackStart && it.date < todayStart }
    if (recent.isEmpty()) return null
    val grouped = recent.groupBy { Pair(it.categoryName, it.amount) }
    val best = grouped.maxByOrNull { it.value.size } ?: return null
    if (best.value.size < config.minOccurrences) return null
    // 今日若已记该分类，则不提醒
    if (bills.any { it.billType == "EXPENSE" && it.date >= todayStart && it.categoryName == best.key.first }) return null
    return HabitReminder(window.label, best.key.first, best.key.second)
}
```

> 上面中间那段注释掉的 `if` 是示意，最终实现直接以精简版为准（去掉那个残缺 `if` 块）。

- [ ] **Step 7: 跑测试验证 + Commit**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.insight.InsightServiceTest"`
Expected: PASS（自然Query 脱敏 + habitReminder 三用例）。

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/insight/InsightServiceTest.kt
git commit -m "feat(server): 问账脱敏只送分类+金额+日期；加习惯提醒 habitReminder"
```

- [ ] **Step 8: polishHabitCopy（LLM 润色，只喂聚合，失败回退模板）**

在 `InsightService` 加：

```kotlin
suspend fun polishHabitCopy(habit: HabitReminder): String {
    val context = """
习惯提醒线索（聚合，无任何明细/备注）:
- 时段: ${habit.label}
- 常记分类: ${habit.categoryName}
- 常记金额: ¥${"%.2f".format(habit.amount)}
请用一句话（≤30字）给出自然亲切、不硬性的提醒文案，例如「${habit.label}时段你常点 ${habit.categoryName} ¥${"%.2f".format(habit.amount)}，记得记一笔吗？」
返回JSON: {"answer": "..."}
""".trimIndent()
    return try {
        val jsonStr = llmParser.chat("你是一个贴心的记账提醒助手，用中文。必须返回 JSON 对象。", context)
        val parsed = jsonStr?.let { kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<QueryResponse>(it) }
        if (!parsed.answer.isBlank()) parsed.answer
        else "「${habit.label}」你常记 ${habit.categoryName} ¥${"%.2f".format(habit.amount)}，今天记了吗？"
    } catch (_: Exception) {
        "「${habit.label}」你常记 ${habit.categoryName} ¥${"%.2f".format(habit.amount)}，今天记了吗？"
    }
}
```

（`polishHabitCopy` 在 `InsightServiceTest` 可选，用假 LLM 不便——不加单测，交给联调。）

---

## Task 2: 用户开关 UsersTable.ai_disabled + UserService

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/tables/UsersTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/UserService.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/UserServiceTest.kt`（新建）

**Interfaces:**
- Produces: `UserInfo.aiDisabled: Boolean`；`UserService.findAllBoundQq(): List<UserInfo>`；`UserService.setAiDisabled(id: Long, disabled: Boolean)`；`UserService.isAiDisabled(id: Long): Boolean`。

- [ ] **Step 1: UsersTable 加列**

```kotlin
val aiDisabled = bool("ai_disabled").default(false)
```

- [ ] **Step 2: 失败测试**

`UserServiceTest.kt`（新建，含 `TestDatabase` + 建表 + insertUser）：

```kotlin
@Test
fun `setAiDisabled and isAiDisabled roundtrip`() {
    // insert 1L,默认 false；setAiDisabled(1L,true) → isAiDisabled(1L)==true；setAiDisabled(1L,false) → false
}
@Test
fun `findAllBoundQq returns only users with qqOpenid`() {
    // 1 有 openid，2 无 → 只返回 1；含 aiDisabled 字段
}
```

- [ ] **Step 3: 实现 UserService**

`UserInfo` 加 `val aiDisabled: Boolean = false`。给 `findById/findByPhone/findByQQ/findByQqOpenid` 的 `UserInfo(...)` 全部补 `aiDisabled = it[UsersTable.aiDisabled]`；抽出私有映射辅助避免重复。加：

```kotlin
fun findAllBoundQq(): List<UserInfo> = transaction {
    UsersTable.selectAll().where { UsersTable.qqOpenid.isNotNull() }.map { it.toUserInfo() }
}

fun setAiDisabled(userId: Long, disabled: Boolean) {
    transaction { UsersTable.update({ UsersTable.id eq userId }) { it[aiDisabled] = disabled } }
}

fun isAiDisabled(userId: Long): Boolean = transaction {
    UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()?.get(UsersTable.aiDisabled) ?: false
}
```

- [ ] **Step 4: 跑测试 + Commit**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.UserServiceTest"`
Expected: PASS。

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/UsersTable.kt \
        server/src/main/kotlin/com/example/rinklnote/server/services/UserService.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/UserServiceTest.kt
git commit -m "feat(server): users 加 ai_disabled 列 + 读取/写入/遍历已绑定QQ用户"
```

---

## Task 3: push_log 去重表 + Database 注册

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/tables/PushLogTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt`

**Interfaces:**
- Produces: `PushLogTable`（`id/userId/type/dayKey/pushedAt`，唯一索引 `(user_id,type,day_key)`）。

- [ ] **Step 1: 新建 PushLogTable**

```kotlin
package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object PushLogTable : Table("push_log") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val type = varchar("type", 20)      // MONTHLY_SUMMARY / ANOMALY / HABIT
    val dayKey = varchar("day_key", 20) // "yyyy-MM" 或 "yyyy-MM-dd"
    val pushedAt = long("pushed_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_push_log_user_type_day", userId, type, dayKey)
    }
}
```

- [ ] **Step 2: Database.kt 建表 + 索引**

`configureDatabase` 的 `SchemaUtils.createMissingTablesAndColumns(...)` 加 `PushLogTable`；`runMigrations` 的 `indexes` 列表加：

```kotlin
"CREATE UNIQUE INDEX IF NOT EXISTS uq_push_log_user_type_day ON push_log(user_id, type, day_key)"
```

- [ ] **Step 3: 编译验证 + Commit**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL。

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/PushLogTable.kt \
        server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt
git commit -m "feat(server): push_log 去重表(按用户+类型+日历键唯一)"
```

---

## Task 4: PushScheduler 主循环（可测：三类推送 + 去重 + ai_disabled）

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/PushScheduler.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/PushSchedulerTest.kt`（新建）

**Interfaces:**
- Consumes: `UserService.findAllBoundQq/isAiDisabled`、`InsightService.monthlySummary/anomalyCheck/habitReminder/polishHabitCopy`、`BillService.monthlyStats`、`QQBotService.sendC2CMessage`、`PushLogTable`。
- Produces: `PushScheduler(userService, send, monthlyProvider, anomalyProvider, habitProvider, clock, intervalMs, log)`，`start(scope)`，`suspend tick()`。为可测，三类内容通过 suspend lambda 注入（`monthlyProvider` 直接回 null 即不推），`send` 用 `suspend (openid:String, content:String, msgId:String)->Boolean`。

- [ ] **Step 1: 设计并实现 PushScheduler**

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.HabitReminder
import com.example.rinklnote.server.tables.PushLogTable
import io.ktor.util.logging.Logger
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class PushScheduler(
    private val userService: UserService,
    private val send: suspend (openid: String, content: String, msgId: String) -> Boolean,
    private val monthlyProvider: suspend (userId: Long, month: String) -> String?,
    private val anomalyProvider: suspend (userId: Long) -> String?,
    private val habitProvider: suspend (userId: Long) -> String?,
    private val clock: () -> LocalDateTime = { LocalDateTime.now(SHANGHAI) },
    private val intervalMs: Long = 30_000L,
    private val log: Logger
) {
    companion object { private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai") }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { log.warn("PushScheduler tick failed: ${e.message}") }
                delay(intervalMs)
            }
        }
    }

    suspend fun tick() {
        val now = clock()
        val today = now.toLocalDate()
        val dayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val month = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val isLastDayOfMonth = today.dayOfMonth == today.lengthOfMonth()
        val hour = now.hour

        for (u in userService.findAllBoundQq()) {
            if (u.aiDisabled) continue        // Q10=A：关所有主动推送
            val openid = u.qqOpenid ?: continue
            try {
                if (isLastDayOfMonth && hour >= 20 && !alreadyPushed(u.id, "MONTHLY_SUMMARY", month)) {
                    val content = monthlyProvider(u.id, month)
                    if (content != null) {
                        if (send(openid, content, UUID.randomUUID().toString())) {
                            markPushed(u.id, "MONTHLY_SUMMARY", month)
                            log.info("月结已推送 user=${u.id}")
                        } else log.warn("月结发送失败 user=${u.id}")
                    }
                }
                if (!alreadyPushed(u.id, "ANOMALY", dayKey)) {
                    val content = anomalyProvider(u.id)
                    if (content != null) {
                        if (send(openid, content, UUID.randomUUID().toString())) {
                            markPushed(u.id, "ANOMALY", dayKey)
                            log.info("异常已推送 user=${u.id}")
                        } else log.warn("异常发送失败 user=${u.id}")
                    }
                }
                if (!alreadyPushed(u.id, "HABIT", dayKey)) {
                    val content = habitProvider(u.id)
                    if (content != null) {
                        if (send(openid, content, UUID.randomUUID().toString())) {
                            markPushed(u.id, "HABIT", dayKey)
                            log.info("习惯已推送 user=${u.id}")
                        } else log.warn("习惯发送失败 user=${u.id}")
                    }
                }
            } catch (e: Exception) {
                log.warn("push user=${u.id} failed: ${e.message}")
            }
        }
    }

    private fun alreadyPushed(userId: Long, type: String, dayKey: String): Boolean = transaction {
        PushLogTable.selectAll().where {
            (PushLogTable.userId eq userId) and (PushLogTable.type eq type) and (PushLogTable.dayKey eq dayKey)
        }.empty().not()
    }

    private fun markPushed(userId: Long, type: String, dayKey: String) {
        transaction {
            val dup = PushLogTable.selectAll().where {
                (PushLogTable.userId eq userId) and (PushLogTable.type eq type) and (PushLogTable.dayKey eq dayKey)
            }.any()
            if (!dup) PushLogTable.insert {
                it[PushLogTable.userId] = userId
                it[PushLogTable.type] = type
                it[PushLogTable.dayKey] = dayKey
                it[pushedAt] = System.currentTimeMillis()
            }
        }
    }
}
```

- [ ] **Step 2: Wrap providers 给 Application.kt 用（本 Task 只写提供者的装配思路，实际接线在 Task 6）**

`monthlyProvider`：用 `billService.monthlyStats(userId, monthStart, monthEnd)` 判断本月有 ≥1 笔（`totalExpense>0 || totalIncome>0`），否则 `null`；有则调 `insightService.monthlySummary(userId, month)` 拼纯文本。
`anomalyProvider`：`insightService.anomalyCheck(userId).alerts` 非空则拼文本，否则 `null`。
`habitProvider`：`insightService.habitReminder(userId)` 非空则 `insightService.polishHabitCopy(habit)`，否则 `null`。

- [ ] **Step 3: 失败测试 — 去重 / ai_disabled / 三类推送**

`PushSchedulerTest.kt`（用 `TestDatabase` + 建表 + insertUser + 假 `send` 记录调用）：

```kotlin
@Test
fun `monthly pushes once per month then dedup`() {
    val sent = mutableListOf<String>()
    val sched = PushScheduler(
        userService, { o, c, _ -> sent.add(c); true }, // send
        monthlyProvider = { _, _ -> "📊 本月总结\n..." },
        anomalyProvider = { _ -> null },
        habitProvider = { _ -> null },
        clock = { LocalDateTime.of(2026, 8, 31, 20, 0) }, // 月末≥20:00
        intervalMs = 30_000, log = log
    )
    sched.tick(); sched.tick() // 同一 tick 内连续两次
    assertEquals(1, sent.size)
}
@Test
fun `ai_disabled user receives nothing`() {
    // setAiDisabled(1, true) → 三类 provider 都返回非空仍不发送
}
@Test
fun `anomaly and habit push only when provider non-null`() {
    // anomalyProvider 返回 "⚠️..." → 发送1次；habitProvider 返回 null → 不发送 habit
}
```

- [ ] **Step 4: 实现补齐 + 跑测试**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.PushSchedulerTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/PushScheduler.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/PushSchedulerTest.kt
git commit -m "feat(server): PushScheduler 日历窗口+push_log去重 三类主动推送,ai_disabled跳过"
```

---

## Task 5: AuthRoutes 开关端点 + MeResponse.aiDisabled

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/AuthRoutes.kt`

**Interfaces:**
- Produces: `GET /api/auth/ai` → `{"disabled": bool}`；`PUT /api/auth/ai`（body `{"disabled": bool}`）；`MeResponse` 加 `aiDisabled`。

- [ ] **Step 1: MeResponse 加字段**

```kotlin
@Serializable
data class MeResponse(
    val id: Long, val phone: String,
    val qqNumber: String? = null, val qqOpenid: String? = null,
    val createdAt: String? = null, val aiDisabled: Boolean = false
)
```

`/me` 处加 `aiDisabled = user.aiDisabled`。

- [ ] **Step 2: 加两个端点（`authenticate("auth-jwt")` 内）**

```kotlin
@Serializable
data class AiSettingRequest(val disabled: Boolean)

get("/ai") {
    val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
        ?: return@get call.respond(HttpStatusCode.Unauthorized)
    call.respond(mapOf("disabled" to userService.isAiDisabled(userId)))
}

put("/ai") {
    val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
        ?: return@put call.respond(HttpStatusCode.Unauthorized)
    val body = call.receive<AiSettingRequest>()
    userService.setAiDisabled(userId, body.disabled)
    call.respond(mapOf("disabled" to body.disabled))
}
```

- [ ] **Step 3: 编译 + Commit**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL。

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/AuthRoutes.kt
git commit -m "feat(server): /api/auth/ai 读写开关 + me 返回 aiDisabled"
```

---

## Task 6: 装配 PushScheduler 到 Application

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/Application.kt`

**Interfaces:**
- Consumes: 现有 `billService`、`insightService`、`qqBotService`、`userService`、`appScope`。

- [ ] **Step 1: 在 `qqBotService.loadFromDb()` 之后、`routing{}` 之前，构造并启动 PushScheduler**

```kotlin
val pushScheduler = PushScheduler(
    userService = userService,
    send = { openid, content, msgId -> qqBotService.sendC2CMessage(openid, content, msgId) },
    monthlyProvider = { userId, month ->
        val (y, m) = month.split("-").map { it.toInt() }
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val monthStart = java.time.LocalDate.of(y, m, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthStart = java.time.LocalDate.of(y, m, 1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        if (stats.totalExpense <= 0 && stats.totalIncome <= 0) null
        else {
            val r = insightService.monthlySummary(userId, month)
            buildString {
                append("📊 本月总结\n")
                append(r.summary)
                r.highlights.forEach { append("\n• ").append(it) }
            }
        }
    },
    anomalyProvider = { userId ->
        val alerts = insightService.anomalyCheck(userId).alerts
        if (alerts.isEmpty()) null
        else alerts.joinToString("\n") { "⚠️ " + it.message }
    },
    habitProvider = { userId ->
        val habit = insightService.habitReminder(userId)
        if (habit == null) null
        else insightService.polishHabitCopy(habit)
    },
    intervalMs = 30_000L,
    log = log
)
pushScheduler.start(appScope)
```

（`PushScheduler` 的 `log` 参数类型是 `io.ktor.util.logging.Logger`，Ktor 的 `log` 即为该类型，可直接传。）

- [ ] **Step 2: 编译 + 全量服务端测试**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test`
Expected: 全绿（含既有 BillServiceTest 等）。

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/Application.kt
git commit -m "feat(server): 装配 PushScheduler 到 appScope"
```

---

## Task 7: App 关闭 AI 主动推送开关

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/AuthViewModel.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/profile/ProfileScreen.kt`

**Interfaces:**
- Produces: `MeResponse.aiDisabled`；`ApiService.setAiDisabled(disabled: Boolean)`；`AuthState.aiDisabled`；`AuthEvent.SetAiDisabled(disabled)`。

- [ ] **Step 1: DTO 加字段与方法**

`MeResponse` 加 `val aiDisabled: Boolean = false`。加：

```kotlin
@Serializable
data class AiDisabledRequest(val disabled: Boolean)
```

- [ ] **Step 2: ApiService 加端点**

```kotlin
@PUT("api/auth/ai") suspend fun setAiDisabled(@Body req: AiDisabledRequest): MessageResponse
```

- [ ] **Step 3: AuthViewModel**

`AuthState` 加 `val aiDisabled: Boolean = false`。`AuthEvent` 加 `data class SetAiDisabled(val disabled: Boolean) : AuthEvent`。`onEvent` 分发：

```kotlin
is AuthEvent.SetAiDisabled -> setAiDisabled(event.disabled)
```

`fetchProfile` 里 `copy(... )` 加 `aiDisabled = me.aiDisabled`。加私有：

```kotlin
private fun setAiDisabled(disabled: Boolean) {
    viewModelScope.launch {
        try {
            api.setAiDisabled(AiDisabledRequest(disabled))
            _state.update { it.copy(aiDisabled = disabled) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "设置失败: ${e.message}") }
        }
    }
}
```

- [ ] **Step 4: ProfileScreen 开关行**

在 `FuncBox`（或顶层 `ProfileScreen` 的 `LazyColumn` 中 state 可用处）加一行开关。推荐在 `FuncBox` 顶部「修改密码」上方加：

```kotlin
Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text("关闭 AI 主动推送", fontSize = 15.sp)
    Switch(checked = state.aiDisabled, onCheckedChange = { onSetAiDisabled(it) })
}
HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
```

为此 `FuncBox` 加一个 `onSetAiDisabled: (Boolean) -> Unit` 参数；`ProfileScreen` 调用处传 `{ authViewModel.onEvent(AuthEvent.SetAiDisabled(it)) }`。

- [ ] **Step 5: 编译 + 单测 + Commit**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: 编译通过。

```bash
git add app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt \
        app/src/main/java/com/example/rinklnote/data/network/ApiService.kt \
        app/src/main/java/com/example/rinklnote/ui/viewmodel/AuthViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/profile/ProfileScreen.kt
git commit -m "feat(app): 我的页加关闭AI主动推送开关"
```

---

## Task 8: Web 关闭 AI 推送开关

**Files:**
- Modify: `web/index.html`

**Interfaces:**
- Consumes: `GET /api/auth/ai`、`PUT /api/auth/ai`（`api()` 已带 JWT；`api()` 已把 HTTP 状态存 `S._lastHttp`）。

- [ ] **Step 1: 在 renderSettings 的「同步」卡片之后加「AI 推送」卡片**

```js
// AI 推送开关 — 关闭后服务端不再主动推送月结/异常/习惯；问账仍可用
const aiCard = h('div',{className:'card',style:'margin-bottom:16px'});
aiCard.append(h('div',{className:'section-label'},'AI 主动推送'));
const aiRow = h('div',{style:'display:flex;align-items:center;justify-content:space-between;margin-top:8px'});
aiRow.append(h('p',{style:'font-size:13px;color:var(--muted);margin:0'},'关闭后不再收到月结/异常/习惯提醒'));
const aiToggle = h('button');
aiRow.appendChild(aiToggle);
aiCard.appendChild(aiRow);
pg.appendChild(aiCard);

// 异步读取当前状态并渲染开关
(async () => {
  let disabled = false;
  try { const r = await api('/api/auth/ai'); disabled = !!r.disabled; } catch(e) {}
  function paint() {
    aiToggle.className = 'toggle' + (disabled ? ' on' : '');
    aiToggle.textContent = disabled ? '已关闭' : '开启';
  }
  aiToggle.onclick = async () => {
    const next = !disabled;
    try {
      await api('/api/auth/ai', { method:'PUT', body: JSON.stringify({ disabled: next }) });
      disabled = next; paint(); S.msg = next ? '已关闭主动推送' : '已开启主动推送'; render();
    } catch(e) { S.msg='设置失败'; render(); }
  };
  paint();
})();
```

（若项目已有 `.toggle` 样式（浅色/深色按钮用），复用 class 即可；`S.msg` 需在页面有显示区域，否则直接内联提示。）

- [ ] **Step 2: 校验 JS + Commit**

Run: `node -c web/index.html`（若无 node 则跳过，改用手测）。手动检查 `aiToggle`、`api('/api/auth/ai')` 拼写。

```bash
git add web/index.html
git commit -m "feat(web): 设置页加AI主动推送开关(读写/api/auth/ai)"
```

---

## Task 9: 全量验证 + 汇报

- [ ] **Step 1:** `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test` 全绿。
- [ ] **Step 2:** `./gradlew :app:assembleDebug` 编译通过。
- [ ] **Step 3:** 冒烟（部署后）：`GET /api/auth/ai` 无 token 401、有 token 返回 `{"disabled":false}`；`PUT /api/auth/ai {"disabled":true}` 后 `GET` 返回 `true`；服务器日志确认 `PushScheduler` 30s tick 无异常。
- [ ] **Step 4: 提交说明（未部署前）**：主动推送需服务端部署到生产（`server:installDist` → tar → scp → systemctl restart）后才能验证真实推送；**habitReminder 依赖 suggest_config 开启**（`loadSuggestConfig` 默认 enabled），若线上未配置则习惯提醒不触发——这是预期行为。
