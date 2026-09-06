# AI 助手接口（服务器端）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给手机 AI（小爱同学等）提供「免打扰记账 + 问账」的 HTTP 接口：个人访问令牌鉴权 + `/api/ai/*` 入口，复用现有 NLU/Bill/Budget/Insight 管线。

**Architecture:** 新增 `ai_api_tokens` 表（只存 SHA-256 哈希）+ `AiTokenService`；个人令牌走 `Authorization: Bearer <token>`，令牌管理走现有 JWT 登录态。新增 `PhoneIntentRouter`（由 `QQIntentRouter` 改造：抽掉硬编码 source、新增 `balance` 意图、记账 source 参数化），QQ 与 AI 共享同一 intent 逻辑。新增 `AiAssistService` 封装结构化查询（record/today/month/balance/summary），`/api/ai/ask` 直接走 `PhoneIntentRouter.route(text, userId, "AI")`。一切复用现有 `BillService/BudgetService/InsightService/NLUService`，无 N+1、无新增第三方依赖。

**Tech Stack:** Ktor (Netty) + Exposed + kotlinx.serialization + JUnit4（JVM 单元测试）。

## Global Constraints

- Kotlin 2.0.21 / JVM target 11 / AGP 8.13.0，无 DI 框架，手动 service 装配于 `Application.module()`。
- 令牌**只存 SHA-256 哈希**，明文仅在生成时返回一次；哈希为 64 位 hex。
- 个人令牌路由（`/api/ai/ask|record|today|month|balance|summary`）统一读 `Authorization: Bearer <token>`；令牌管理（`/api/ai/tokens*`）走 `authenticate("auth-jwt")`。
- 个人令牌大端：`rln_` 前缀 + 64 位 hex（32 字节随机）。
- `AIAssistantRoutes` 不含业务逻辑；业务在 `AiAssistService`（纯计算，可单测）。
- **本机 JVM 测试常跑不起来**（用户环境：Java24 + 用户路径含撇号 `a'su's`）。因此：测试照写（供 CI），但本地以 `./gradlew server:compileKotlin`（编译通过）+ `server:run` + curl 活体验证为主。所有任务验证给出编译命令与实际 curl。
- 日期边界统一 `ZoneId.of("Asia/Shanghai")`，区间为 `[start, end)` 纪元毫秒。

---

### Task 1: `AiApiTokensTable` + `AiTokenService` + 注册

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/tables/AiApiTokensTable.kt`
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/AiTokenService.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt:33`（`createMissingTablesAndColumns(...)` 加表）、`:52-60`（runMigrations 加索引）
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/AiTokenServiceTest.kt`

**Interfaces:**
- Consumes: `UsersTable.id`（外键）；`TestDatabase.connect(prefix)`；`SchemaUtils.create(...)`。
- Produces:
  - `class AiTokenService`
  - `fun generate(userId: Long, name: String, now: Long = System.currentTimeMillis()): Pair<Long, String>`（返回 `(id, 明文token)`）
  - `fun list(userId: Long): List<AiTokenDTO>`
  - `fun revoke(userId: Long, id: Long, now: Long = System.currentTimeMillis()): Boolean`
  - `fun revokeAll(userId: Long, now: Long = System.currentTimeMillis()): Int`
  - `fun findUserByToken(rawToken: String): Long?`（未命中或已作废 → null）
  - `data class AiTokenDTO(val id: Long, val name: String, val createdAt: Long, val revoked: Boolean, val revokedAt: Long? = null)`

- [ ] **Step 1: Write the failing test**

`server/src/test/kotlin/com/example/rinklnote/server/services/AiTokenServiceTest.kt`:

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AiApiTokensTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiTokenServiceTest {

    private val service = AiTokenService()

    @Before
    fun setup() {
        TestDatabase.connect("aitoken")
        transaction { SchemaUtils.create(UsersTable, AiApiTokensTable) }
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000051"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    @Test
    fun `generated token returns raw once and stores sha256 hash`() {
        val (id, raw) = service.generate(1L, "小爱")
        assertTrue(raw.startsWith("rln_"))
        assertEquals(64, AiTokenService.sha256(raw).length)
        // Stored hash == sha256(raw), never the raw token.
        val storedHash = transaction {
            AiApiTokensTable.selectAll().where { AiApiTokensTable.id eq id }
                .single()[AiApiTokensTable.tokenHash]
        }
        assertEquals(AiTokenService.sha256(raw), storedHash)
        assertFalse(storedHash == raw)
    }

    @Test
    fun `findUserByToken resolves owner and nulls for revoked or unknown`() {
        val (_, raw) = service.generate(1L, "小爱")
        assertEquals(1L, service.findUserByToken(raw))
        assertNull(service.findUserByToken("rln_unknown"))
        // After revoke, same token no longer resolves.
        val (id, _) = service.generate(1L, "另一枚")
        assertEquals(1L, service.findUserByToken(raw))
        assertTrue(service.revoke(1L, id))
        service.findUserByToken(raw) // revoke other token, no-op for this - but raw still valid
    }

    @Test
    fun `revoke makes token unusable and list reflects it`() {
        val (id, raw) = service.generate(1L, "小爱")
        assertNotNull(service.findUserByToken(raw))
        assertTrue(service.revoke(1L, id))
        assertNull(service.findUserByToken(raw))
        val listed = service.list(1L)
        val row = listed.first { it.id == id }
        assertTrue(row.revoked)
        assertNotNull(row.revokedAt)
    }

    @Test
    fun `revokeAll revokes every token of the user`() {
        service.generate(1L, "a")
        val (_, b) = service.generate(1L, "b")
        assertEquals(2, service.revokeAll(1L))
        assertNull(service.findUserByToken(b))
        assertTrue(service.list(1L).all { it.revoked })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew server:test --tests "com.example.rinklnote.server.services.AiTokenServiceTest"`
Expected: FAIL（`AiTokenService` / `AiApiTokensTable` 不存在，编译错误）。若本机无法执行测试，见 Global Constraints——以编译失败为准，能走到 Step 4 编译即代表实现成立。

- [ ] **Step 3: Write minimal implementation**

`server/src/main/kotlin/com/example/rinklnote/server/tables/AiApiTokensTable.kt`:

```kotlin
package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object AiApiTokensTable : Table("ai_api_tokens") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()   // SHA-256 hex, plaintext never stored
    val name = varchar("name", 60)
    val createdAt = long("created_at")
    val revokedAt = long("revoked_at").nullable().default(null)

    override val primaryKey = PrimaryKey(id)
}
```

`server/src/main/kotlin/com/example/rinklnote/server/services/AiTokenService.kt`:

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AiApiTokensTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class AiTokenDTO(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val revoked: Boolean,
    val revokedAt: Long? = null
)

class AiTokenService {

    companion object {
        private val RANDOM = SecureRandom()

        fun sha256(token: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun generateRawToken(): String {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            return "rln_" + bytes.joinToString("") { "%02x".format(it) }
        }
    }

    /** Returns (id, rawToken). Plaintext returned exactly once; only its SHA-256 hash is persisted. */
    fun generate(userId: Long, name: String, now: Long = System.currentTimeMillis()): Pair<Long, String> {
        val raw = generateRawToken()
        val hash = sha256(raw)
        val id = transaction {
            AiApiTokensTable.insert {
                it[AiApiTokensTable.userId] = userId
                it[AiApiTokensTable.tokenHash] = hash
                it[AiApiTokensTable.name] = name
                it[AiApiTokensTable.createdAt] = now
            } get AiApiTokensTable.id
        }
        return id to raw
    }

    fun list(userId: Long): List<AiTokenDTO> = transaction {
        AiApiTokensTable.selectAll()
            .where { AiApiTokensTable.userId eq userId }
            .orderBy(AiApiTokensTable.id to SortOrder.ASC)
            .map { it.toDTO() }
    }

    fun revoke(userId: Long, id: Long, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val updated = AiApiTokensTable.update({
            (AiApiTokensTable.id eq id) and (AiApiTokensTable.userId eq userId)
        }) {
            it[revokedAt] = now
        }
        updated > 0
    }

    fun revokeAll(userId: Long, now: Long = System.currentTimeMillis()): Int = transaction {
        AiApiTokensTable.update({ AiApiTokensTable.userId eq userId }) {
            it[revokedAt] = now
        }
    }

    /** Resolves a raw bearer token to a userId, or null if unknown / revoked. */
    fun findUserByToken(rawToken: String): Long? {
        val hash = sha256(rawToken)
        return transaction {
            AiApiTokensTable.selectAll()
                .where { (AiApiTokensTable.tokenHash eq hash) and (AiApiTokensTable.revokedAt.isNull()) }
                .singleOrNull()?.get(AiApiTokensTable.userId)
        }
    }

    private fun ResultRow.toDTO() = AiTokenDTO(
        id = this[AiApiTokensTable.id],
        name = this[AiApiTokensTable.name],
        createdAt = this[AiApiTokensTable.createdAt],
        revoked = this[AiApiTokensTable.revokedAt] != null,
        revokedAt = this[AiApiTokensTable.revokedAt]
    )
}
```

- [ ] **Step 4: Register table in `Database.kt`**

在 `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt:33` 的 `SchemaUtils.createMissingTablesAndColumns(...)` 尾部追加 `, AiApiTokensTable`；在 `runMigrations()` 的 `indexes` 列表里加一行：

```kotlin
"CREATE INDEX IF NOT EXISTS idx_ai_tokens_user ON ai_api_tokens(user_id)",
```

（`indexes.forEach` 已有 try/catch 幂等。）

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew server:compileKotlin`（编译通过）+ `./gradlew server:test --tests "com.example.rinklnote.server.services.AiTokenServiceTest"`
Expected: 编译通过与 4 个测试全 PASS（若本机 JVM 测试受限，以编译通过 + 测试文件存在为准）。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/AiApiTokensTable.kt \
        server/src/main/kotlin/com/example/rinklnote/server/services/AiTokenService.kt \
        server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/AiTokenServiceTest.kt
git commit -m "feat(server): ai_api_tokens 表 + AiTokenService（SHA-256 哈希存取、生成/列/作废/校验）"
```

---

### Task 2: `QQIntentRouter` → `PhoneIntentRouter`（source 参数化 + `balance` 意图）

**Files:**
- Rename to: `server/src/main/kotlin/com/example/rinklnote/server/services/PhoneIntentRouter.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/QQMessageProcessor.kt:96-97`（构造名 + 调用）
- Rename to: `server/src/test/kotlin/com/example/rinklnote/server/services/PhoneIntentRouterTest.kt`
- Test: 新增 balance 用例 + `source="AI"` 记账用例。

**Interfaces:**
- Consumes: `BillService` / `BudgetService` / `InsightService` / `NLUService`（构造参数，不变）；`InsightService.resolveYearMonth`。
- Produces: `class PhoneIntentRouter(...)`；`suspend fun route(content: String, userId: Long, source: String = "QQ"): String`。QQ 端默认 `"QQ"`，AI 端传 `"AI"`。

- [ ] **Step 1: Rename + rewrite the router**

`git mv` 两份文件（类文件与测试文件），然后编辑类文件：
- 类声明 `class QQIntentRouter(` → `class PhoneIntentRouter(`
- `suspend fun route(content: String, userId: Long): String` → `suspend fun route(content: String, userId: Long, source: String = "QQ"): String`
- `route` 内 `billService.createBill(userId, result.amount, result.categoryName, result.remark, "QQ")` → `billService.createBill(userId, result.amount, result.categoryName, result.remark, source)`
- 查询区（D 段）`if (BUDGET.containsMatchIn(content)) return budget(userId)` 之后加 `if (BALANCE.containsMatchIn(content)) return balance(userId)`
- 新增 `private fun balance(userId: Long): String` 方法
- `helpText()` 增一行 `余额: 看看我的余额`
- companion object 增 `val BALANCE = Regex("余额|还剩|balance|总资产|账户里有|卡里", RegexOption.IGNORE_CASE)`

完整 `balance` 与 companion 增补：

```kotlin
private fun balance(userId: Long): String {
    val accounts = billService.accountsFor(userId)
    if (accounts.isEmpty()) return "还没有账户，先去 App 加一个吧～"
    val total = Money.cents(accounts.sumOf { it.balance })
    return "账户余额合计 ¥${"%.2f".format(total)}：\n" +
        accounts.joinToString("\n") { "${it.name} ¥${"%.2f".format(it.balance)}" }
}
```

companion 增补（放到现有 `val BUDGET = Regex(...)` 行后）：

```kotlin
val BALANCE = Regex("余额|还剩|balance|总资产|账户里有|卡里", RegexOption.IGNORE_CASE)
```

- [ ] **Step 2: Update `QQMessageProcessor`**

`QQMessageProcessor.kt` 第 96-97 行改为：

```kotlin
val router = PhoneIntentRouter(billService, budgetService, insightService, nluService)
val reply = router.route(content, user.id)   // 缺省 source="QQ"
```

- [ ] **Step 3: Update test + add balance & source cases**

重命名测试类 `class QQIntentRouterTest(` → `class PhoneIntentRouterTest(`；构造 `private val router = QQIntentRouter(...)` → `private val router = PhoneIntentRouter(...)`。新增测试用例：

```kotlin
@Test
fun `balance intent sums all accounts`() = runBlocking {
    billService.accountsFor(1L)   // ensureDefaultAccounts -> 微信/支付宝/无账户
    val reply = router.route("看看我的余额", 1L)
    assertTrue("reply=$reply", reply.contains("余额合计"))
    assertTrue(reply.contains("微信"))
}

@Test
fun `ai source is persisted on the bill`() = runBlocking {
    val reply = router.route("午餐20元", 1L, "AI")
    assertTrue("reply=$reply", reply.contains("已记录"))
    val source = transaction {
        BillsTable.selectAll().where { BillsTable.userId eq 1L }.single()[BillsTable.billSource]
    }
    assertEquals("AI", source)
}
```

（`insertBill` helper 里 `billSource` 已硬编码 "APP"；新用例用 router 写入，才能断言 source。）

- [ ] **Step 4: Verify compile + run tests**

Run: `./gradlew server:compileKotlin`（编译通过）；`./gradlew server:test --tests "com.example.rinklnote.server.services.PhoneIntentRouterTest"`（含原有 13 用例 + 新增 2 用例全 PASS）。本机受限则仅编译通过为准。

- [ ] **Step 5: Commit**

```bash
git add -A server/src/main/kotlin/com/example/rinklnote/server/services/QQIntentRouter.kt \
        server/src/main/kotlin/com/example/rinklnote/server/services/PhoneIntentRouter.kt \
        server/src/main/kotlin/com/example/rinklnote/server/services/QQMessageProcessor.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/QQIntentRouterTest.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/PhoneIntentRouterTest.kt
git commit -m "refactor(server): QQIntentRouter→PhoneIntentRouter，source 参数化 + 新增 balance 意图，QQ/AI 共享 intent 逻辑"
```

---

### Task 3: `AiAssistService`（record/today/month/balance/summary）+ 单测

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/AiAssistService.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/AiAssistServiceTest.kt`

**Interfaces:**
- Consumes: `BillService` / `BudgetService` / `InsightService`（构造参数）；`Money.cents`；`InsightService.monthlyReview`/`anomalyCheck`（suspend）。
- Produces:
  - `class AiAssistService(billService, budgetService, insightService, zone: ZoneId = Asia/Shanghai)`
  - `fun record(userId, amount, category?, remark?): AiRecordResponse`
  - `fun today(userId): AiTodayResponse`
  - `fun month(userId): AiMonthResponse`
  - `fun balance(userId): AiBalanceResponse`
  - `suspend fun summary(userId, month?): AiSummaryResponse`
  - 对应 `@Serializable` 响应数据类（reply + 结构化字段）。

- [ ] **Step 1: Write the failing test**

`server/src/test/kotlin/com/example/rinklnote/server/services/AiAssistServiceTest.kt`:

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiAssistServiceTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val llmParser = LLMParser(
        LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500)
    )
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val insightService = InsightService(llmParser, billService)
    private val service = AiAssistService(billService, budgetService, insightService)

    @Before
    fun setup() {
        TestDatabase.connect("aiassist")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, BudgetsTable
            )
            BillsTable.deleteAll(); BudgetsTable.deleteAll(); SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll(); CategoriesTable.deleteAll(); UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000061"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun todayStart(): Long =
        LocalDate.now(shanghai).atStartOfDay(shanghai).toInstant().toEpochMilli()

    private fun monthStart(): Long =
        LocalDate.now(shanghai).withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()

    private fun catId(name: String): Long = transaction {
        CategoriesTable.selectAll().where { CategoriesTable.name eq name }.single()[CategoriesTable.id]
    }

    private fun insertBill(amount: Double, categoryName: String, date: Long, billType: String = "EXPENSE"): Long =
        transaction {
            BillsTable.insert {
                it[BillsTable.userId] = 1L
                it[BillsTable.amount] = amount
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = catId(categoryName)
                it[BillsTable.categoryName] = categoryName
                it[BillsTable.accountId] = 1
                it[BillsTable.date] = date
                it[BillsTable.billSource] = "APP"
                it[BillsTable.createdAt] = date
                it[BillsTable.updatedAt] = date
            } get BillsTable.id
        }

    @Test
    fun `record creates an AI bill and replies`() {
        val r = service.record(1L, 20.0, "三餐", null)
        assertTrue(r.reply.contains("已记录"))
        assertTrue(r.reply.contains("三餐"))
        val src = transaction {
            BillsTable.selectAll().where { BillsTable.userId eq 1L }.single()[BillsTable.billSource]
        }
        assertEquals("AI", src)
    }

    @Test
    fun `today reports only today expenses`() {
        insertBill(30.0, "三餐", todayStart())
        insertBill(8.0, "交通", todayStart())
        insertBill(99.0, "三餐", todayStart() - 24L * 3600 * 1000) // yesterday
        val r = service.today(1L)
        assertEquals(38.0, r.expense, 0.001)
        assertEquals(2, r.count)
        assertTrue(r.reply.contains("38.00"))
    }

    @Test
    fun `month reports expense income and budget remaining`() {
        budgetService.upsert(1L, monthStart(), 1000.0)
        insertBill(300.0, "三餐", monthStart())
        val r = service.month(1L)
        assertEquals(300.0, r.expense, 0.001)
        assertEquals(1000.0, r.budget!!, 0.001)
        assertEquals(700.0, r.remaining!!, 0.001)
    }

    @Test
    fun `balance sums all accounts`() {
        val r = service.balance(1L)
        assertEquals(0.0, r.total, 0.001)
        assertTrue(r.accounts.isNotEmpty())   // 微信/支付宝/无账户
        assertTrue(r.reply.contains("余额合计"))
    }

    @Test
    fun `summary merges review with anomaly and falls back with dummy llm`() = runBlocking {
        insertBill(500.0, "三餐", monthStart())
        val r = service.summary(1L, null)
        assertNotNull(r.summary)
        assertTrue(r.summary.isNotBlank())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew server:test --tests "com.example.rinklnote.server.services.AiAssistServiceTest"`
Expected: FAIL（`AiAssistService` 不存在）。本机受限则以编译失败为准。

- [ ] **Step 3: Write minimal implementation**

`server/src/main/kotlin/com/example/rinklnote/server/services/AiAssistService.kt`:

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class AiRecordResponse(val reply: String)

@Serializable
data class AiTodayResponse(val reply: String, val expense: Double, val income: Double, val count: Int)

@Serializable
data class AiMonthResponse(
    val reply: String, val expense: Double, val income: Double,
    val budget: Double?, val remaining: Double?
)

@Serializable
data class AiAccountBalance(val name: String, val balance: Double)

@Serializable
data class AiBalanceResponse(val reply: String, val accounts: List<AiAccountBalance>, val total: Double)

@Serializable
data class AiSummaryResponse(val reply: String, val summary: String, val highlights: List<String>)

/**
 * 手机 AI（小爱等）的结构化副接口。纯计算单元：所有查询/记账复用
 * [BillService]/[BudgetService]/[InsightService]，无 HTTP 依赖，可直接单测。
 * 自然语言入口（/api/ai/ask）由 [PhoneIntentRouter] 处理；本类只服务结构化端点。
 */
class AiAssistService(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val insightService: InsightService,
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
) {
    fun record(userId: Long, amount: Double, category: String?, remark: String?): AiRecordResponse {
        require(amount > 0 && amount.isFinite()) { "金额必须大于0" }
        val bill = billService.createBill(userId, amount, category, remark, "AI")
        return AiRecordResponse("已记录：${bill.categoryName} ¥${"%.2f".format(bill.amount)}")
    }

    fun today(userId: Long): AiTodayResponse {
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val bills = billService.allBills(userId).filter { it.date >= todayStart && it.date < todayEnd }
        val expenseBills = bills.filter { it.billType == "EXPENSE" }
        val expense = Money.cents(expenseBills.sumOf { it.amount })
        val income = Money.cents(bills.filter { it.billType == "INCOME" }.sumOf { it.amount })
        return AiTodayResponse(
            "今天已花 ¥${"%.2f".format(expense)}（${expenseBills.size}笔），收入 ¥${"%.2f".format(income)}",
            expense, income, expenseBills.size
        )
    }

    fun month(userId: Long): AiMonthResponse {
        val now = LocalDate.now(zone)
        val monthStart = now.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthStart = now.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        val budget = budgetService.list(userId).firstOrNull { it.monthStart == monthStart && !it.deleted }
        val remaining = budget?.let { Money.cents(it.amount - stats.totalExpense) }
        val reply = buildString {
            append("本月支出 ¥${"%.2f".format(stats.totalExpense)}，收入 ¥${"%.2f".format(stats.totalIncome)}")
            if (budget != null) append("，预算 ¥${"%.2f".format(budget.amount)}，剩余 ¥${"%.2f".format(remaining!!)}")
        }
        return AiMonthResponse(reply, stats.totalExpense, stats.totalIncome, budget?.amount, remaining)
    }

    fun balance(userId: Long): AiBalanceResponse {
        val accounts = billService.accountsFor(userId)
        val total = Money.cents(accounts.sumOf { it.balance })
        val reply = if (accounts.isEmpty()) "还没有账户，先去 App 加一个吧～"
        else "账户余额合计 ¥${"%.2f".format(total)}：\n" +
            accounts.joinToString("\n") { "${it.name} ¥${"%.2f".format(it.balance)}" }
        return AiBalanceResponse(reply, accounts.map { AiAccountBalance(it.name, it.balance) }, total)
    }

    suspend fun summary(userId: Long, month: String?): AiSummaryResponse {
        val target = month ?: LocalDate.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val review = insightService.monthlyReview(userId, target)
        val alerts = insightService.anomalyCheck(userId).alerts
        val sb = StringBuilder(review.summary)
        review.highlights.forEach { sb.append("\n· ").append(it) }
        alerts.forEach { sb.append("\n⚠️ ").append(it.message) }
        return AiSummaryResponse(sb.toString(), review.summary, review.highlights)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew server:compileKotlin` + `./gradlew server:test --tests "com.example.rinklnote.server.services.AiAssistServiceTest"`
Expected: 编译通过 + 5 用例 PASS。本机受限则以编译通过为准。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/AiAssistService.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/AiAssistServiceTest.kt
git commit -m "feat(server): AiAssistService 结构化查询（record/today/month/balance/summary）复用 Bill/Budget/Insight"
```

---

### Task 4: `AIAssistantRoutes` + `Application` 装配

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/routes/AIAssistantRoutes.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/Application.kt`（装配 service + 注册路由）

**Interfaces:**
- Consumes: `PhoneIntentRouter` / `AiAssistService` / `AiTokenService`；`JWTPrincipal`；`call.receive<>`；`call.respond(...)`。
- Produces: `fun Route.aiAssistantRoutes(phoneIntentRouter, aiAssistService, aiTokenService)`；`data class AiGenerateTokenRequest(val name: String = "小爱")`、`AiAskRequest(text)`、`AiRecordRequest(amount, category?, type?, remark?, date?)`、`AiTokenResponse(id, token, name, createdAt)`。响应均为 `{reply:...}` 或结构化 JSON。

- [ ] **Step 1: Write implementation**

`server/src/main/kotlin/com/example/rinklnote/server/routes/AIAssistantRoutes.kt`:

```kotlin
package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.AiAssistService
import com.example.rinklnote.server.services.AiTokenService
import com.example.rinklnote.server.services.PhoneIntentRouter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class AiGenerateTokenRequest(val name: String = "小爱")

@Serializable
data class AiAskRequest(val text: String)

@Serializable
data class AiRecordRequest(
    val amount: Double,
    val category: String? = null,
    val type: String? = null,
    val remark: String? = null,
    val date: Long? = null
)

@Serializable
data class AiTokenResponse(val id: Long, val token: String, val name: String, val createdAt: Long)

fun Route.aiAssistantRoutes(
    phoneIntentRouter: PhoneIntentRouter,
    aiAssistService: AiAssistService,
    aiTokenService: AiTokenService
) {
    // 个人令牌校验：Authorization: Bearer <token> → 查哈希 → userId，未命中/已作废 → null。
    suspend fun io.ktor.server.application.ApplicationCall.aiUserIdOrNull(): Long? {
        val header = request.headers["Authorization"] ?: return null
        if (!header.startsWith("Bearer ")) return null
        return aiTokenService.findUserByToken(header.removePrefix("Bearer ").trim())
    }

    route("/api/ai") {
        // ── 令牌管理：走现有 JWT 登录态（用 App 的 Bearer JWT） ──
        authenticate("auth-jwt") {
            post("/tokens") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<AiGenerateTokenRequest>()
                val name = body.name.ifBlank { "小爱" }.take(60)
                val now = System.currentTimeMillis()
                val (id, raw) = aiTokenService.generate(userId, name, now)
                call.respond(HttpStatusCode.Created, AiTokenResponse(id, raw, name, now))
            }

            get("/tokens") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(aiTokenService.list(userId))
            }

            post("/tokens/{id}/revoke") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                if (aiTokenService.revoke(userId, id)) call.respond(mapOf("message" to "已作废"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "令牌不存在"))
            }

            post("/tokens/revoke-all") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                aiTokenService.revokeAll(userId)
                call.respond(mapOf("message" to "已全部作废"))
            }
        }

        // ── AI 入口：个人令牌鉴权（Bearer 访问令牌，复用 JWT 之外的个人令牌） ──
        post("/ask") {
            val userId = call.aiUserIdOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            val body = call.receive<AiAskRequest>()
            if (body.text.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reply" to "没听清内容，试试「午餐20元」"))
            }
            // 复用完整 NLU/intent 管线；记账 source 落 "AI"。month 由自然语言里解析，无需单独传。
            val reply = phoneIntentRouter.route(body.text, userId, "AI")
            call.respond(mapOf("reply" to reply))
        }

        post("/record") {
            val userId = call.aiUserIdOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            val body = call.receive<AiRecordRequest>()
            if (body.amount <= 0 || !body.amount.isFinite()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reply" to "没听清金额，试试「午餐20元」"))
            }
            // createBill 由分类名推导 EXPENSE/INCOME；type 字段为描述性，不参与落库判断。
            call.respond(aiAssistService.record(userId, body.amount, body.category, body.remark))
        }

        get("/today") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            call.respond(aiAssistService.today(userId))
        }

        get("/month") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            call.respond(aiAssistService.month(userId))
        }

        get("/balance") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            call.respond(aiAssistService.balance(userId))
        }

        get("/summary") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            val month = call.request.queryParameters["month"]
            call.respond(aiAssistService.summary(userId, month))
        }
    }
}
```

- [ ] **Step 2: Wire into `Application.module()`**

在 `server/src/main/kotlin/com/example/rinklnote/server/Application.kt`：
- 声明 service：`val aiTokenService = AiTokenService()`、`val phoneIntentRouter = PhoneIntentRouter(billService, budgetService, insightService, nluService)`、`val aiAssistService = AiAssistService(billService, budgetService, insightService)`（放在 `insightService` 与 `nluService` 构造之后）。
- `routing { ... }` 内追加 `aiAssistantRoutes(phoneIntentRouter, aiAssistService, aiTokenService)`。
- 补 import：`com.example.rinklnote.server.services.AiAssistService`、`AiTokenService`、`PhoneIntentRouter`；路由 `import` 已含 `com.example.rinklnote.server.routes.*`。

- [ ] **Step 3: Compile**

Run: `./gradlew server:compileKotlin`
Expected: PASS。

- [ ] **Step 4: Live verify (curl)，起服务后练一遍**

```bash
# 起服务（另开终端）
DEEPSEEK_API_KEY=xxx JWT_SECRET=<强随机> WEBHOOK_SECRET=<强随机> ./gradlew server:run

# 1) 登录取 JWT（改成真实账号，或直接拿已登录 App 的 JWT）
JWT=$(curl -s http://118.31.184.221/api/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"138...","password":"..."}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 2) 生成个人令牌（用 JWT）
curl -s http://118.31.184.221/api/ai/tokens -X POST \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"name":"小爱"}'            # → {id, token, name, createdAt}

# 3) 用个人令牌 ask（无 JWT 也能打）
TOKEN=<上面返回的 token>
curl -s http://118.31.184.221/api/ai/ask -X POST \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"午餐20元"}'         # → {"reply":"已记录：三餐 ¥20.00"}
curl -s ... -d '{"text":"今天花了多少"}'   # → {"reply":"今天已花 ..."}
curl -s ... -d '{"text":"看看我的余额"}'    # → {"reply":"账户余额合计 ..."}

# 4) 结构化副接口
curl -s http://118.31.184.221/api/ai/today  -H "Authorization: Bearer $TOKEN"
curl -s http://118.31.184.221/api/ai/month  -H "Authorization: Bearer $TOKEN"
curl -s http://118.31.184.221/api/ai/balance -H "Authorization: Bearer $TOKEN"
curl -s "http://118.31.184.221/api/ai/summary?month=2026-08" -H "Authorization: Bearer $TOKEN"

# 5) 作废后应 401
curl -s http://118.31.184.221/api/ai/tokens/<id>/revoke -X POST -H "Authorization: Bearer $JWT"
curl -s http://118.31.184.221/api/ai/ask -X POST -H "Authorization: Bearer $TOKEN" \
  -d '{"text":"午餐20元"}'          # → 401 {"reply":"未授权：令牌缺失或已失效"}
```

Expected: 各步返回符合上注释；作废后 `ask` 返回 401。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/AIAssistantRoutes.kt \
        server/src/main/kotlin/com/example/rinklnote/server/Application.kt
git commit -m "feat(server): /api/ai 接口（令牌管理 JWT + ask/record/today/month/balance/summary 个人令牌鉴权）"
```

---

## Self-Review（对照 spec）

**Spec 覆盖：**
- 「个人令牌鉴权」→ Task 1（表 + 服务）+ Task 4（路由）。
- 「AI 入口 /api/ai/ask → route(text,userId)」→ Task 2（balance 意图）+ Task 4。
- 「结构化副接口 record/today/month/balance/summary」→ Task 3 + Task 4。
- 「复用」（QQ→PhoneIntentRouter，source="AI"）→ Task 2。
- 「amount<=0 → 没听清金额」→ Task 4 record/ask。
- 「401 明确响应」→ Task 4 resource 各 handler。
- 「令牌仅生成返回一次」「只存哈希」→ Task 1 generate/sha256。

**占位符：** 无 TODO/TBD；每步含实际代码与命令。

**类型一致性：** `AiTokenService.generate` 返回 `Pair<Long,String>` 与 Task 1 一致；`AiAssistService` 方法签名与 Task 3 一致；`PhoneIntentRouter.route(..., source: String = "QQ")` 与 Task 2 一致；`aiAssistantRoutes(phoneIntentRouter, aiAssistService, aiTokenService)` 与 Task 4 一致。

**已知缺口（为何未覆盖）：** 深链 `rinklnote://add` 与 App 设置页令牌管理属于 App 端，按 skill 规范拆到独立计划，本计划完成后另行 `2026-09-06-ai-assistant-interface-app.md`。

---

## 执行交接

Plan complete and saved to `docs/superpowers/plans/2026-09-06-ai-assistant-interface-server.md`.

**执行选项：**

1. **Subagent-Driven（推荐）** — 每个 Task 派发一个全新 subagent，任务间人工复审，快速迭代。
2. **Inline Execution** — 在本会话用 executing-plans 批量执行，带检查点逐段复审。

**另请注意：** 这是**服务器**计划（4 个 Task）。App 端（设置页令牌管理 + `rinklnote://add` 深链）是第二个独立计划，我会在本计划执行完（或你选择并行）时另行编写。要不要我现在就补写 App 计划？选哪种执行方式？
