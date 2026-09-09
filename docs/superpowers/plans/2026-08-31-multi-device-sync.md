# 多端同步改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 App / Web / QQ 记账数据多端一致：账户按用户隔离并跨端同步、账单编辑用「条件 PUT + 409 重放」防冲突、本地优先(注册才上云)注册时推送本地积压、统一 Asia/Shanghai 时区、修登出丢数。

**Architecture:** 账户从「全局 3 个」改为「每用户一套 + 可增删改」；服务端 `accounts` 加 `user_id`/`deleted`/`updated_at` 并建 `(user_id,name)` 唯一索引，旧全局账户留作惰性数据不删（FK RESTRICT 且用户明确不迁移旧数据）。账单 PUT 加可选 `baseUpdatedAt`，带则校验、不等返 409。App Room v9→v10 加 `bills.base_updated_at` + accounts 同步字段；SyncManager 把账户当账单一样 push/pull(LWW)，账单 push 走条件 PUT + 409 重放、pull 跳过本地 dirty 行。时区统一用 `ZoneId.of("Asia/Shanghai")`（留 `bookkeepingZone()` 作多时区接缝）。

**Tech Stack:** Ktor + Exposed + H2(服务端测试)；Room + KSP + kotlinx-serialization + Retrofit (App)；Vanilla JS SPA (Web)。

## Global Constraints

- 服务端生产：`Database.kt` 用 `createMissingTablesAndColumns` 启动自动加列/建表，**但不会 drop 旧索引**——旧 `accounts.name` 唯一索引必须手动 `DROP`，否则跨用户不能同名账户。生产库为阿里云 H2，旧数据不迁移、不重映射。
- App Room：current=9，本次升 10；`fallbackToDestructiveMigration` 兜底，但必须提供 `MIGRATION_9_10` 保数据。
- 测试：服务端用 `TestDatabase.connect(prefix)`；App 用 JVM JUnit。跑测试：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test`（JDK 用 17，见 gradle.properties）。
- 提交：按路径显式暂存源码，不用 `add -A`，避免 server/build、app/build 产物入库。
- 时区常量：`ZoneId.of("Asia/Shanghai")`（E8），多时区设置本轮不做，仅留接缝。

---

## File Structure

### 服务端 (server/src/main/kotlin/com/example/rinklnote/server)
- `tables/AccountsTable.kt` — 加 `user_id`/`updated_at`/`deleted`，索引改 `(user_id,name)`。
- `services/BillService.kt` — `AccountDTO` 加 `updatedAt/deleted`；新增 `ensureDefaultAccounts/accountsFor/createAccount/updateAccount/deleteAccount`；`createBill`(QQ) 选当前用户账户；`createWebBill` 校验账户属主。
- `plugins/Database.kt` — 启动迁移：手动 drop 旧 `accounts_name` 唯一索引。
- `routes/BillRoutes.kt` — 把 `get /api/bills/accounts` 改为 JWT + 按用户过滤；`put /api/bills/{id}` 加 `baseUpdatedAt` 校验(409)；新增 `get /api/bills/{id}`。
- `routes/AccountRoutes.kt`（新建）— JWT 账户 CRUD：`GET /api/accounts`、`POST /api/accounts`、`PUT /api/accounts/{id}`、`DELETE /api/accounts/{id}`。
- `Application.kt` — 挂载 `AccountRoutes`。

### App (app/src/main/java/com/example/rinklnote)
- `data/db/entity/Bill.kt` — 加 `baseUpdatedAt`(col `base_updated_at`)。
- `data/db/entity/Account.kt` — 加 `serverId/updatedAt/deleted/dirty`，名称唯一索引改 `server_id` 唯一。
- `data/db/AppDatabase.kt` — `MIGRATION_9_10` + version=10。
- `data/db/dao/AccountDao.kt` — 加 `observeAll/getUnsynced/upsert/updateServerId/softDelete/deleteByServerId/getByServerId`。
- `data/network/dto/DTOs.kt` — 加 `AccountDTO/CreateAccountRequest/UpdateAccountRequest`；`CreateBillRequest` 加 `baseUpdatedAt`。
- `data/network/ApiService.kt` — 加 accounts 端点 + `getBill(id)`。
- `data/repository/BillRepository.kt` / `BillRepositoryImpl.kt` — accounts 改 Flow 暴露 + 账户 CRUD/同步方法；`clearLocalData` 修正。
- `sync/SyncManager.kt` — 账户 push/pull(LWW)、账单条件 PUT + 409 重放、pull 跳过本地 dirty、登出 dirty 修复。
- `util/DateUtil.kt` — `bookkeepingZone()`，时区统一上海。
- `ui/viewmodel/QuickAddViewModel.kt` — 账单 `date` 用 Shanghai。
- `ui/viewmodel/BudgetViewModel.kt` / `ui/screen/plan/PlanScreen.kt` — 预算月份用 Shanghai。
- `ui/viewmodel/AssetsViewModel.kt` — 账户增删改走 repository+sync。
- `ui/screen/assets/AssetsScreen.kt`（+ 账户管理 UI）— 账户卡片增删改。

### Web (web/index.html)
- accounts 改 JWT 拉当前用户账户；账户卡片增删改；账单编辑 PUT 带 `baseUpdatedAt` + 处理 409。

### 测试
- 服务端：`services/AccountServiceTest.kt`(新建) + `BillServiceTest.kt` 适配新签名。
- App：`sync/SyncManagerTest.kt`(账号/条件PUT/登出) + 时区。

---

## Task 1: Server AccountsTable + BillService 账户方法

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/tables/AccountsTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/AccountServiceTest.kt`(新建)

**Interfaces:**
- Produces: `AccountsTable.userId`, `AccountsTable.updatedAt`, `AccountsTable.deleted`; `BillService.accountIdFor(userId: Long): Long`, `BillService.ensureDefaultAccounts(userId: Long)`, `BillService.accountsFor(userId: Long): List<AccountDTO>`, `BillService.createAccount(userId, name, iconColor, balance): AccountDTO`, `BillService.renameAccount(id, userId, name, iconColor): AccountDTO?`, `BillService.deleteAccount(id, userId): Boolean`; `AccountDTO.updatedAt`, `AccountDTO.deleted`.
- Consumes: existing `UsersTable` FK, `TestDatabase`.

- [ ] **Step 1: 改 AccountsTable**

```kotlin
package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object AccountsTable : Table("accounts") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id).nullable()
    val name = varchar("name", 50)
    val balance = double("balance").default(0.0)
    val iconColor = varchar("icon_color", 10)
    val updatedAt = long("updated_at").default(0)
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_accounts_user_name", userId, name)
    }
}
```

- [ ] **Step 2: 失败测试 — ensureDefaultAccounts + accountsFor + create/rename/delete**

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountServiceTest {
    private val service = BillService()

    @Before
    fun setup() {
        TestDatabase.connect("accounttest")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable)
            BillsTable.deleteAll(); SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll(); CategoriesTable.deleteAll(); UsersTable.deleteAll()
        }
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000021"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    @Test
    fun `ensureDefaultAccounts seeds the 3 defaults once and is idempotent`() {
        service.ensureDefaultAccounts(1L)
        val first = service.accountsFor(1L)
        assertEquals(listOf("微信", "支付宝", "默认"), first.map { it.name })
        service.ensureDefaultAccounts(1L)
        assertEquals(listOf("微信", "支付宝", "默认"), service.accountsFor(1L).map { it.name })
    }

    @Test
    fun `createAccount adds a custom account only for that user`() {
        val w = service.createAccount(1L, "招商银行", "#123456", 0.0)
        assertTrue(w.id > 0)
        assertEquals(listOf("微信", "支付宝", "默认", "招商银行"), service.accountsFor(1L).map { it.name })
    }

    @Test
    fun `renameAccount changes name and returns dto, null for other user or missing`() {
        val w = service.createAccount(1L, "招商银行", "#123456", 0.0)
        val renamed = service.renameAccount(w.id, 1L, "招行卡", "#000000")
        assertEquals("招行卡", renamed!!.name)
        assertNull(service.renameAccount(w.id, 99999L, "x", "y"))
        assertNull(service.renameAccount(999999, 1L, "x", "y"))
    }

    @Test
    fun `deleteAccount soft-deletes and hides it from accountsFor`() {
        val w = service.createAccount(1L, "招商银行", "#123456", 0.0)
        assertTrue(service.deleteAccount(w.id, 1L))
        assertFalse(service.accountsFor(1L).any { it.id == w.id })
    }

    @Test
    fun `accounts are isolated per user`() {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 2L
                it[UsersTable.phone] = "13800000022"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
        service.ensureDefaultAccounts(1L)
        service.ensureDefaultAccounts(2L)
        service.createAccount(1L, "招商银行", "#123456", 0.0)
        assertEquals(4, service.accountsFor(1L).size)
        assertEquals(3, service.accountsFor(2L).size) // user 2 has no custom account
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.AccountServiceTest"`
Expected: FAIL — `ensureDefaultAccounts` unresolved / `accountsFor` unresolved.

- [ ] **Step 4: 实现 BillService 账户方法 + acccountIdFor**

在 `BillService` 加：

```kotlin
fun ensureDefaultAccounts(userId: Long) {
    val existing = transaction {
        AccountsTable.selectAll()
            .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
            .count()
    }
    if (existing > 0) return
    val now = System.currentTimeMillis()
    transaction {
        listOf(Triple("微信", "#28C145", 0.0), Triple("支付宝", "#06B4FD", 0.0), Triple("默认", "#F97D1D", 0.0))
            .forEach { (name, color, balance) ->
                AccountsTable.insert {
                    it[AccountsTable.userId] = userId
                    it[AccountsTable.name] = name
                    it[AccountsTable.iconColor] = color
                    it[AccountsTable.balance] = balance
                    it[AccountsTable.updatedAt] = now
                }
            }
    }
}

fun accountsFor(userId: Long): List<AccountDTO> {
    ensureDefaultAccounts(userId)
    return transaction {
        AccountsTable.selectAll()
            .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
            .orderBy(AccountsTable.id)
            .map { it.toDto() }
    }
}

fun createAccount(userId: Long, name: String, iconColor: String, balance: Double): AccountDTO {
    require(name.isNotBlank()) { "账户名不能为空" }
    require(balance >= 0 && balance.isFinite()) { "余额不能为负" }
    val now = System.currentTimeMillis()
    val id = transaction {
        AccountsTable.insert {
            it[AccountsTable.userId] = userId
            it[AccountsTable.name] = name
            it[AccountsTable.iconColor] = iconColor
            it[AccountsTable.balance] = balance
            it[AccountsTable.updatedAt] = now
        } get AccountsTable.id
    }
    return AccountDTO(id, name, balance, iconColor, now, false)
}

fun renameAccount(id: Long, userId: Long, name: String, iconColor: String): AccountDTO? = transaction {
    val row = AccountsTable.selectAll()
        .where { (AccountsTable.id eq id) and (AccountsTable.userId eq userId) }
        .singleOrNull() ?: return@transaction null
    val now = System.currentTimeMillis()
    AccountsTable.update({ AccountsTable.id eq id }) {
        it[AccountsTable.name] = name
        it[AccountsTable.iconColor] = iconColor
        it[AccountsTable.updatedAt] = now
    }
    row.toDto().copy(name = name, iconColor = iconColor, updatedAt = now)
}

fun deleteAccount(id: Long, userId: Long): Boolean {
    val now = System.currentTimeMillis()
    return transaction {
        val updated = AccountsTable.update({
            (AccountsTable.id eq id) and (AccountsTable.userId eq userId)
        }) { it[deleted] = true; it[updatedAt] = now }
        updated > 0
    }
}

/** 当前用户 首个未删除账户；无则先播种默认。 */
fun accountIdFor(userId: Long): Long {
    ensureDefaultAccounts(userId)
    return transaction {
        AccountsTable.selectAll()
            .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
            .orderBy(AccountsTable.id)
            .first()[AccountsTable.id]
    }
}
```

并给 `AccountDTO` 加字段（默认值，兼容旧构造调用）：

```kotlin
@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    val balance: Double,
    val iconColor: String,
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)
```

给 `AccountsTable` 加一个私有映射助手（`BillService` 内）：

```kotlin
private fun org.jetbrains.exposed.sql.ResultRow.toDto() = AccountDTO(
    id = this[AccountsTable.id], name = this[AccountsTable.name],
    balance = this[AccountsTable.balance], iconColor = this[AccountsTable.iconColor],
    updatedAt = this[AccountsTable.updatedAt], deleted = this[AccountsTable.deleted]
)
```

- [ ] **Step 5: 改 createBill(QQ) 选当前用户账户**

把 `createBill` 里这段：
```kotlin
val account = transaction {
    AccountsTable.selectAll().orderBy(AccountsTable.id).firstOrNull()
        ?: throw IllegalStateException("No account found")
}
```
换成：
```kotlin
val accountId = accountIdFor(userId)
val account = transaction { AccountsTable.selectAll().where { AccountsTable.id eq accountId }.single() }
```
并在 `createWebBill` 开头加属主校验（旧全局账户 userId 为 NULL 会被拒；新用户账户已按 userId 播种）：

```kotlin
val owns = transaction {
    AccountsTable.selectAll().where { (AccountsTable.id eq accountId) and (AccountsTable.userId eq userId) }.any()
}
require(owns) { "账户不存在" }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.AccountServiceTest"`
Expected: PASS

- [ ] **Step 7: 适配旧 BillServiceTest**

`getAccounts()` 签名废弃 → 把 `updateAccountBalance` 相关测试改为经 `accountsFor(1L)` 取账户（`updateAccountBalance(id, balance, userId)` 后面在 Task 3 改签名，但先加 `userId` 参数保持编译）。同时 `seedIfNeeded` 里 `seedAccounts()`（全局 3 账户）不再创建——改为只在 `AccountsTable` 空时播种一条 null-user 数据避免 FK 空洞？**决定：`seedAccounts()` 保留做「零用户全局兜底」但不再被当作目标账户**。最简单：`seedIfNeeded` 仍然 `if (AccountsTable.selectAll().empty()) seedAccounts()` 创建全局 null-user 账户（旧逻辑），新用户账户由 `ensureDefaultAccounts` 创建。这样旧测试 `service.getAccounts()` 若被移除则改为 `accountsFor(1L)`。

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/AccountsTable.kt \
        server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/AccountServiceTest.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/BillServiceTest.kt
git commit -m "feat(server): 每用户账户模型 — accounts 加 user_id/deleted/updated_at + 账户 CRUD 与 QQ 落账选当前用户账户"
```

---

## Task 2: Server accounts 路由 + 账单条件 PUT + Database 索引迁移

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/routes/AccountRoutes.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/Application.kt`

**Interfaces:**
- Consumes: `BillService.accountsFor/createAccount/renameAccount/deleteAccount/accountIdFor`; `BillService.syncBills`; existing JWT `authenticate("auth-jwt")`.
- Produces: 路由 `GET /api/accounts`、`POST /api/accounts`、`PUT /api/accounts/{id}`、`DELETE /api/accounts/{id}`；`GET /api/bills/{id}`；`PUT /api/bills/{id}` 支持 `baseUpdatedAt`(409)。

- [ ] **Step 1: AccountRoutes.kt(新建)**

```kotlin
package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CreateAccountRequest(val name: String, val iconColor: String, val balance: Double = 0.0)

@Serializable
data class UpdateAccountRequest(val name: String? = null, val iconColor: String? = null, val balance: Double? = null)

fun Route.accountRoutes(billService: BillService) {
    authenticate("auth-jwt") {
        route("/api/accounts") {
            get {
                val userId = call.userId()
                call.respond(billService.accountsFor(userId))
            }
            post {
                val userId = call.userId()
                val body = call.receive<CreateAccountRequest>()
                try {
                    val dto = billService.createAccount(userId, body.name, body.iconColor, body.balance)
                    call.respond(HttpStatusCode.Created, dto)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "请求不合法")))
                }
            }
            put("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                val body = call.receive<UpdateAccountRequest>()
                val dto = billService.renameAccount(id, userId, body.name ?: "", body.iconColor ?: "")
                if (body.balance != null) {
                    require(body.balance >= 0 && body.balance.isFinite()) { "余额不能为负" }
                    billService.updateAccountBalance(id, body.balance, userId)
                }
                if (dto != null) {
                    val fresh = billService.accountsFor(userId).firstOrNull { it.id == id } ?: dto
                    call.respond(fresh)
                } else call.respond(HttpStatusCode.NotFound, mapOf("message" to "账户不存在"))
            }
            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                if (billService.deleteAccount(id, userId)) call.respond(mapOf("message" to "已删除"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "账户不存在"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.userId(): Long =
    this.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
        ?: error("Unauthorized")
```

- [ ] **Step 2: BillService.updateAccountBalance 加 userId 属主**

把 `updateAccountBalance(id, balance)` 改为 `updateAccountBalance(id, balance, userId)`，只在 `(id eq) and (userId eq)` 命中时更新，否则 null。

- [ ] **Step 3: BillRoutes — accounts 改 JWT + 单笔 GET + 条件 PUT**

`get("/api/bills/accounts")` 从 public 段挪到 `authenticate("auth-jwt")` 内并改：
```kotlin
get("/api/bills/accounts") {
    val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
        ?: return@get call.respond(HttpStatusCode.Unauthorized)
    call.respond(billService.accountsFor(userId))
}
```
`CreateBillRequest` 加 `val baseUpdatedAt: Long? = null`。`put("/{id}")` 在取到 row 后、更新前加：
```kotlin
if (body.baseUpdatedAt != null && row[BillsTable.updatedAt] != body.baseUpdatedAt) {
    call.respond(HttpStatusCode.Conflict, BillDTO(/*当前 row 的最新 DTO*/))
    return@put
}
```
加 `get("/{id}")`：
```kotlin
get("/{id}") {
    val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
        ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
    val dto = billService.getBill(id, userId)
    if (dto != null) call.respond(dto) else call.respond(HttpStatusCode.NotFound, mapOf("message" to "账单不存在"))
}
```
`BillService.getBill(id, userId): BillDTO?` 需要新增：`BillsTable.selectAll().where { (id eq) and (userId eq) }.singleOrNull()?.let { it.toBillDto() }`。

- [ ] **Step 4: Database.kt drop 旧 accounts 唯一索引**

在 `Database.kt` 建连后加一次性迁移（Idempotent：H2 若索引不存在 `DROP INDEX IF EXISTS`）。Exposed `.uniqueIndex()` 生成的旧索引名是 `accounts_name`，但保险起见按 `accounts_name_unique`/`accounts_name` 各尝试，或直接查询系统表。**简化**：用 `transaction { exec("DROP INDEX IF EXISTS accounts_name") ; exec("DROP INDEX IF EXISTS accounts_name_unique") }`。随后 `SchemaUtils.createMissingTablesAndColumns` 会建新表/列与 `uq_accounts_user_name`。

- [ ] **Step 5: Application.kt 挂路由**

`application { ...; accountRoutes(billService); }` 在 `billRoutes(...)` 附近注册。

- [ ] **Step 6: 服务端测试（路由级可省略，服务测试即可）+ 跑全量**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test`
Expected: 全绿（含既有 BillServiceTest 适配后）。

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/AccountRoutes.kt \
        server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt \
        server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt \
        server/src/main/kotlin/com/example/rinklnote/server/Application.kt \
        server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt
git commit -m "feat(server): 账户 CRUD 路由(按用户) + 账单条件 PUT(409) + 单笔 GET + 旧 accounts 索引迁移"
```

---

## Task 3: App Room v9→v10 迁移

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/entity/Bill.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/entity/Account.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/AccountDao.kt`
- Test: `app/src/test/.../RoomMigrationTest.kt`(新建)

**Interfaces:**
- Produces: `Bill.baseUpdatedAt`; `Account` 增 `serverId/updatedAt/deleted/dirty` + `AccountDao` 新增方法；`MIGRATION_9_10`。

- [ ] **Step 1: Bill.kt 加 baseUpdatedAt**

```kotlin
@ColumnInfo(name = "base_updated_at") val baseUpdatedAt: Long? = null, // 最后被服务端确认的 updatedAt
```
（放在 `updatedAt` 之后）并更新 `toRequest()`/构造点兼容（见 Task 4/5）。

- [ ] **Step 2: Account.kt 同步字段**

```kotlin
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["server_id"], unique = true)]
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balance: Double = 0.0,
    @ColumnInfo(name = "icon_color") val iconColor: String,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false
)
```

- [ ] **Step 3: AccountDao 扩展**

```kotlin
@Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY id ASC")
fun observeAll(): Flow<List<Account>>

@Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY id ASC")
suspend fun getAllActive(): List<Account>

@Query("SELECT * FROM accounts WHERE server_id IS NULL OR dirty = 1")
suspend fun getUnsynced(): List<Account>

@Query("SELECT * FROM accounts WHERE server_id = :serverId")
suspend fun getByServerId(serverId: Long): Account?

@androidx.room.Upsert
suspend fun upsert(account: Account)

@Query("DELETE FROM accounts WHERE server_id = :serverId")
suspend fun deleteByServerId(serverId: Long)

@Query("UPDATE accounts SET server_id = :serverId, updated_at = :updatedAt, dirty = 0 WHERE id = :localId")
suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)

@Query("UPDATE accounts SET dirty = 1, deleted = 1, updated_at = :updatedAt WHERE id = :id")
suspend fun softDelete(id: Long, updatedAt: Long)

@Query("DELETE FROM accounts")
suspend fun deleteAll()
```
（保留 `insert`/`getAll` 若仍被 seed 使用则改名 `getAllActive`。`getAll` 若被引用改指向新方法。）

- [ ] **Step 4: AppDatabase.kt version=10 + MIGRATION_9_10**

```kotlin
version = 10,
...
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN base_updated_at INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE accounts ADD COLUMN server_id INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE accounts ADD COLUMN updated_at INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE accounts ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_server_id ON accounts(server_id)")
        db.execSQL("DROP INDEX IF EXISTS index_accounts_name")
    }
}
.addMigrations(..., MIGRATION_9_10)
```
Room 校验要求 entity 与迁移后 schema 一致：`accounts` 不再有 `index_accounts_name`(实体已删 name 唯一索引)，且有 `index_accounts_server_id`。

- [ ] **Step 5: 失败迁移测试**

```kotlin
@Test
fun migration_9_10_addsBillBaseUpdatedAt_andAccountSyncColumns() {
    // 用 in-memory + MigrationTestHelper；或直接对已建 v9 schema 执行迁移后断言列存在。
}
```
（若项目无 MigrationTestHelper 依赖，则此项改为「编译期验证 schema」：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest`，Room schema 校验在开 DB 时抛 `IllegalStateException` 若不一致——用现有的 DB-open 测试或依赖 `:app:lint`/`kaptDebugKotlin`。）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/data/db/entity/Bill.kt \
        app/src/main/java/com/example/rinklnote/data/db/entity/Account.kt \
        app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt \
        app/src/main/java/com/example/rinklnote/data/db/dao/AccountDao.kt
git commit -m "feat(app): Room v9→v10 — bills 加 base_updated_at, accounts 加同步字段与 server_id 唯一索引"
```

---

## Task 4: App DTO / ApiService / Repository

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/repository/BillRepository.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt`

**Interfaces:**
- Produces: `AccountDTO/CreateAccountRequest/UpdateAccountRequest`; `ApiService.getAccounts/createAccount/updateAccount/deleteAccount/getBill`; `CreateBillRequest.baseUpdatedAt`; `BillRepository` 新方法 `observeAccounts/insertAccount/updateAccountLocal/softDeleteAccount/markAccountSynced/getUnsyncedAccounts/getAccountByServerId/deleteAccountByServerId`。

- [ ] **Step 1: DTOs.kt 加账户 + baseUpdatedAt**

```kotlin
@Serializable
data class AccountDTO(
    val id: Long, val name: String, val balance: Double,
    val iconColor: String, val updatedAt: Long? = null, val deleted: Boolean = false
)
@Serializable
data class CreateAccountRequest(val name: String, val iconColor: String, val balance: Double = 0.0)
@Serializable
data class UpdateAccountRequest(val name: String? = null, val iconColor: String? = null, val balance: Double? = null)
```
`CreateBillRequest` 加 `val baseUpdatedAt: Long? = null`（默认 null 兼容旧 POST）。

- [ ] **Step 2: ApiService.kt 加端点**

```kotlin
@GET("api/accounts") suspend fun getAccounts(): List<AccountDTO>
@POST("api/accounts") suspend fun createAccount(@Body req: CreateAccountRequest): AccountDTO
@PUT("api/accounts/{id}") suspend fun updateAccount(@Path("id") id: Long, @Body req: UpdateAccountRequest): AccountDTO
@DELETE("api/accounts/{id}") suspend fun deleteAccount(@Path("id") id: Long): MessageResponse
@GET("api/bills/{id}") suspend fun getBill(@Path("id") id: Long): BillDTO
```

- [ ] **Step 3: BillRepository 接口 + impl**

接口新增：
```kotlin
override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll()
override suspend fun insertAccount(a: Account): Long = accountDao.insert(a)
override suspend fun updateAccountLocal(a: Account) = accountDao.update(a)
override suspend fun softDeleteAccount(a: Account) = accountDao.softDelete(a.id, System.currentTimeMillis())
override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) = accountDao.updateServerId(localId, serverId, updatedAt)
override suspend fun getUnsyncedAccounts(): List<Account> = accountDao.getUnsynced()
override suspend fun getAccountByServerId(serverId: Long): Account? = accountDao.getByServerId(serverId)
override suspend fun deleteAccountByServerId(serverId: Long) = accountDao.deleteByServerId(serverId)
```
`clearLocalData` 修正 + 账户清空：
```kotlin
override suspend fun clearLocalData() {
    billDao.deleteSynced() // 见 Task 5 改 SQL
    budgetDao.deleteAll()
    templateDao.deleteAll()
    chatDao.deleteAll()
    accountDao.deleteSyncedClean() // WHERE server_id IS NOT NULL AND dirty = 0
}
```
（`accountDao` 需加 `deleteSyncedClean`；否则用 `deleteAll` 会丢本地匿名期自建账户——但登出本就该清已同步、保留未推送，故与 bills 同理。）

补 `loadReferenceData` 里 accounts 用 `getAllActive()`，并让 `observeAccounts` 成为账户唯一来源（移除 `_accounts` StateFlow 的静态读取，改由 Room Flow 驱动）。

- [ ] **Step 4: 编译验证 + Commit**

```bash
git add app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt \
        app/src/main/java/com/example/rinklnote/data/network/ApiService.kt \
        app/src/main/java/com/example/rinklnote/data/repository/BillRepository.kt \
        app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt
git commit -m "feat(app): 账户 DTO/API + repository 同步接口；CreateBillRequest 加 baseUpdatedAt"
```

---

## Task 5: SyncManager 账户同步 + 条件 PUT + 登出修复

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/sync/SyncManager.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/BillDao.kt`(deleteSynced 修正)
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/AccountDao.kt`(deleteSyncedClean)
- Test: `app/src/test/.../sync/SyncManagerTest.kt`

**Interfaces:**
- Consumes: 新增 `Account` DAO/API 方法、`Bill.baseUpdatedAt`、`getBill`。
- Produces: 账户 push/pull(LWW)、账单条件 PUT + 409 重放、pull 跳过本地 dirty。

- [ ] **Step 1: BillDao.deleteSynced 修正**

```sql
DELETE FROM bills WHERE server_id IS NOT NULL AND dirty = 0
```
（保留未推送的本地编辑/新建行。）

- [ ] **Step 2: AccountDao.deleteSyncedClean**

```sql
DELETE FROM accounts WHERE server_id IS NOT NULL AND dirty = 0
```

- [ ] **Step 3: SyncManager push/pull 账户 + 账单条件 PUT**

- 构造注入 `accountDao: AccountDao? = null`。
- `sync()` 在推送账单后、拉取移动新增「账户同步」；`syncAccounts()` 类似 `syncBudgets`：先推 unsynced(create→POST /api/accounts, serverId!=null→PUT update, deleted→DELETE 并本地 hard delete)，再 `getAccounts()` 全量 LWW（`serverTime > local.updateAt` 才覆盖；本地 dirty 行跳过 push pull）。
- 账单 push 分支 `bill.serverId != null` 改条件 PUT：`toRequest()` 带 `baseUpdatedAt = bill.baseUpdatedAt`；捕获 `HttpException`(409) → `getBill(serverId)` 取新 base → `bill.copy(baseUpdatedAt = fresh.updatedAt)` → 重 `updateBill` 一次 → `updateServerId(...)`。
- pull 合并 `alive.map` 时给 `Bill` 设 `baseUpdatedAt = dto.updatedAt`；且对每个 dto，若本地已有同 serverId 且 `dirty=true` 的行，**跳过**（不覆盖未推送的本地编辑）。

- [ ] **Step 4: 失败测试 — 账户 LWW / 409 重放 / 登出保留 dirty**

```kotlin
@Test fun accountPushCreatesThenPullLwwNotOverwriteDirty() { /* mock api/dao */ }
@Test fun billUpdate409ReplaysWithFreshBaseOnce() { /* Retrofit(HttpException 409) → 重放 */ }
@Test fun billSyncSkipsLocalDirtyOnPull() { /* 本地 dirty 优先 */ }
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/sync/SyncManager.kt \
        app/src/main/java/com/example/rinklnote/data/db/dao/BillDao.kt \
        app/src/main/java/com/example/rinklnote/data/db/dao/AccountDao.kt
git commit -m "feat(app): SyncManager 账户双向同步 + 账单条件 PUT 409 重放 + pull 跳过本地 dirty + 登出保留未推送"
```

---

## Task 6: AssetsViewModel 账户管理 + App UI

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/AssetsViewModel.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt`

**Interfaces:**
- Consumes: `BillRepository` 账户 CRUD/sync 方法、`SyncManager.pushAccount`(新增)。

**Goal:** 资产页账户卡片可新建/重命名/删除 + 改余额；本地匿名期自建账户 serverId=null，注册登录后随 full sync 推上云。

- [ ] **Step 1: AssetsViewModel** 加事件 `AddAccount(name,color)/RenameAccount(id,name,color)/DeleteAccount(account)/ChangeBalance(...)`，分别落 `repository.insertAccount/updateAccountLocal/softDeleteAccount` 并 `syncManager.pushAccount(...)` 后台推。pushAccount 需要在 SyncManager 暴露：`suspend fun pushAccount(account: Account) = syncMutex.withLock { pushOneAccount(account) }`（create/update/delete 分支，写后盖 serverId）。

- [ ] **Step 2: AssetsScreen** 加账户卡片「+ 新建」「编辑」「删除」入口（下拉/长按菜单），复用现有账户卡片组件。余额编辑沿用 `BalanceEditDialog`。

- [ ] **Step 3: 编译 + Commit**

```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/AssetsViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt \
        app/src/main/java/com/example/rinklnote/sync/SyncManager.kt
git commit -m "feat(app): 资产页账户增删改 + 余额跨端（本地优先，注册后推云）"
```

---

## Task 7: 时区统一 Asia/Shanghai

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/util/DateUtil.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/BudgetViewModel.kt`(如涉及) + `ui/screen/plan/PlanScreen.kt`

**Interfaces:**
- Produces: `fun bookkeepingZone(): ZoneId = ZoneId.of("Asia/Shanghai")`（多时区接缝）。

- [ ] **Step 1: DateUtil** 加 `bookkeepingZone()`，`getMonthStart/getNextMonthStart/toDateString/toDayOfWeek/toHeaderString/toDateTimestamp` 全部 `ZoneId.systemDefault()` → `bookkeepingZone()`。
- [ ] **Step 2: QuickAddViewModel:347** `ZoneId.systemDefault()` → `bookkeepingZone()`。
- [ ] **Step 3: Budget month 计算** 同样替换（`getMonthStart` 已集中，如直接使用则自动生效；若有内联 `ZoneId.systemDefault()` 替换）。
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/util/DateUtil.kt \
        app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/viewmodel/BudgetViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/plan/PlanScreen.kt
git commit -m "feat(app): 记账时区统一 Asia/Shanghai(bookkeepingZone 接缝)"
```

---

## Task 8: Web 每用户账户 + 账单 409

**Files:**
- Modify: `web/index.html`

**Interfaces:**
- Consumes: 服务端 `GET /api/accounts`(JWT)、账户 CRUD、`PUT /api/bills/{id}` `baseUpdatedAt`。

- [ ] **Step 1:** account 列表改为 JWT `GET /api/accounts`；账户卡片渲染 + 增删改按钮（弹各自 prompt/表单 → `POST/PUT/DELETE /api/accounts`）。余额编辑 PUT 带 `name/iconColor/balance`。
- [ ] **Step 2:** 账单编辑 `PUT /api/bills/{id}` body 加 `baseUpdatedAt: bill.updatedAt`；响应 409 时提示「账单已在其他设备修改」并重新拉取该行。
- [ ] **Step 3: Commit**

```bash
git add web/index.html
git commit -m "feat(web): 每用户账户增删改 + 账单编辑带 baseUpdatedAt 处理 409"
```

---

## Task 9: 全量验证

- [ ] **Step 1:** `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test` 全绿。
- [ ] **Step 2:** `./gradlew :app:assembleDebug` 编译通过。
- [ ] **Step 3:** 冒烟：server 重启 active，`GET /api/accounts` 无 token 401、有 token 返回当前用户 3 默认账户；`PUT /api/bills/{id}` 带错误 base 返 409。
- [ ] **Step 4: 汇报（未装机/未部署前同步备注）。**
