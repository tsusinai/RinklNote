# 资产管理重构 · 计划 1：账户模型 + 数据契约 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `Account` 引入一等 `type`（资产类型）、`isLiability`（负债标志）、`openingBalance`（期初余额）三个字段，并让 App 与服务端的 DTO/Sync 契约承载它们。本计划**纯增量**，不改变"余额仍手动维护"的现状语义——余额推导与 UI 放在计划 2。

**Architecture:** App Room `accounts` 表加三列（`v10→v11` 手写 `MIGRATION_10_11`），服务端 `accounts` 表靠 `SchemaUtils.createMissingTablesAndColumns` 自动加列 + `AccountDTO`/请求体扩展；两端 `SyncManager` 的 LWW 合并与 push/pull 覆盖新字段；服务端放宽非负债类的"余额不为负"守卫；App 新建账户弹窗改为选类型（最小 UI，完整资产页 UI 在计划 2）。

**Tech Stack:** Kotlin 2.0.21 / Compose / Room 2.6.1 (KSP) / kotlinx-serialization-json 1.7.3；服务端 Ktor + Exposed。

## Global Constraints

- 类型值用字符串枚举（与既有 `billType` 风格一致）：`CASH`(现金) / `BANK_CARD`(银行卡) / `WECHAT`(微信) / `ALIPAY`(支付宝) / `VIRTUAL`(虚拟) / `OTHER`(其他) / `CREDIT`(信用卡·负债) / `LOAN`(贷款·负债)。
- `isLiability = true` 仅 `CREDIT`、`LOAN`。
- 所有版本号走 `gradle/libs.versions.toml`，不硬编码。
- **验证环境**：本机跑 JVM 测试受 Java24 + 路径撇号影响；用 `export GRADLE_USER_HOME=D:/gradle && ./gradlew ...`（JDK17 由 gradle.properties 指定）尝试，失败则改用**编译验证** `:app:compileDebugKotlin` / `:server:compileKotlin`。涉及纯逻辑（类型判定/期初换算）另用独立入口验证。
- **提交卫生**：按精确路径暂存源码，**不要 `git add -A`**；跳过 `server/build`、`.claude/settings.local.json`、`.idea`；`app/schemas/*.json`（Room 导出 schema）要一起提交。
- 每个任务结束跑：`:app:compileDebugKotlin` + `:server:compileKotlin`。

---

### Task 1: App Account 实体加字段

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/entity/Account.kt`

**Interfaces:**
- Produces: `Account(type: String = "OTHER", isLiability: Boolean = false, openingBalance: Double = 0.0)` — 后续任务/计划都依赖这三个属性。

- [ ] **Step 1: 改实体，追加三个字段**

```kotlin
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["server_id"], unique = true)]
)
@androidx.compose.runtime.Immutable
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balance: Double = 0.0,
    val type: String = "OTHER",
    @androidx.room.ColumnInfo(name = "is_liability") val isLiability: Boolean = false,
    @androidx.room.ColumnInfo(name = "opening_balance") val openingBalance: Double = 0.0,
    @androidx.room.ColumnInfo(name = "icon_color") val iconColor: String,
    @androidx.room.ColumnInfo(name = "server_id") val serverId: Long? = null,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false
)
```

- [ ] **Step 2: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（Room 会因 schema 变化要求导出；若编译报 schema 校验错，进入 Task 2 后统一处理）。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/db/entity/Account.kt
git commit -m "feat(app): Account 增加 type/isLiability/openingBalance 字段"
```

---

### Task 2: Room 迁移 v10→11（accounts 加三列 + 数据回填）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt`
- Test: `app/src/test/java/com/example/rinklnote/data/db/AccountMigrationTest.kt`（可选，编译验证兜底）

**Interfaces:**
- Consumes: `Account` 新字段（Task 1）。
- Produces: `MIGRATION_10_11`；DB `version = 11`。
- 关键：迁移把 `type` 按名称回填（微信→WECHAT，支付宝→ALIPAY，其余 OTHER），`opening_balance` = `balance`（保留当下数字；余额推导的计划 2 会读到这个值）。

- [ ] **Step 1: 改版本号 + 迁移列表 + 手写迁移**

```kotlin
@Database(
    entities = [Bill::class, Category::class, SubCategory::class, Account::class, BillTemplate::class, Budget::class, ChatMessage::class],
    version = 11,
    exportSchema = true
)
// ...

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN type TEXT NOT NULL DEFAULT 'OTHER'")
        db.execSQL("ALTER TABLE accounts ADD COLUMN is_liability INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN opening_balance REAL NOT NULL DEFAULT 0")
        db.execSQL("UPDATE accounts SET type = CASE WHEN name='微信' THEN 'WECHAT' WHEN name='支付宝' THEN 'ALIPAY' WHEN name='银行卡' THEN 'BANK_CARD' ELSE 'OTHER' END")
        // 期初 = 现值 − Σ(历史账单增减)，保证计划 2 的 balance=期初+Σ账单 在迁移后不跳变、不重复计算
        db.execSQL("UPDATE accounts SET opening_balance = balance - COALESCE((SELECT SUM(CASE bill_type WHEN 'INCOME' THEN amount WHEN 'EXPENSE' THEN -amount ELSE 0 END) FROM bills WHERE bills.account_id = accounts.id AND bills.deleted = 0), 0)")
    }
}

// 在数据库构建链 addMigrations(...) 末尾追加：
// .addMigrations(MIGRATION_1_2, ..., MIGRATION_9_10, MIGRATION_10_11)
```

> 注：`fallbackToDestructiveMigration()` 仍在，先保证 `MIGRATION_10_11` 注册，避免误触发毁灭性回退。迁移后需同步导出 schema 到 `app/schemas/`。

- [ ] **Step 2: 编译验证 + 检查导出 schema**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL；`app/schemas/com.example.rinklnote.data.db.AppDatabase/11.json` 已生成。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt app/schemas
git commit -m "feat(app): Room v10→11 accounts 加 type/isLiability/opening_balance 并回填"
```

---

### Task 3: 服务端 accounts 表 + DTO + 请求体扩展

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/tables/AccountsTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt`（`AccountDTO`，约 55-63）
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/AccountRoutes.kt`（`CreateAccountRequest`/`UpdateAccountRequest`，约 14/17）

**Interfaces:**
- Produces: `AccountDTO` 新字段；`CreateAccountRequest`/`UpdateAccountRequest` 新字段。

- [ ] **Step 1: AccountsTable 加列（Exposed，运行期自动建）**

```kotlin
// server/src/main/kotlin/com/example/rinklnote/server/tables/AccountsTable.kt
object AccountsTable : Table("accounts") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id).nullable()
    val name = varchar("name", 50)
    val balance = double("balance").default(0.0)
    val type = varchar("type", 20).default("OTHER")
    val isLiability = bool("is_liability").default(false)
    val openingBalance = double("opening_balance").default(0.0)
    val iconColor = varchar("icon_color", 10)
    val updatedAt = long("updated_at").default(0)
    val deleted = bool("deleted").default(false)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("uq_accounts_user_name", userId, name) }
}
```

> `SchemaUtils.createMissingTablesAndColumns`（`plugins/Database.kt:33`）会自动加列，无需手写列迁移。仅当新增索引才需在 `runMigrations` 处理；本任务不加索引。

- [ ] **Step 2: AccountDTO 扩展（BillService.kt 约 55-63）**

```kotlin
/*
@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    val balance: Double,
    val type: String,
    val isLiability: Boolean,
    val openingBalance: Double,
    val iconColor: String,
    val updatedAt: Long,
    val deleted: Boolean
)
复制 Row 时：type = row[AccountsTable.type], isLiability = row[AccountsTable.isLiability], openingBalance = row[AccountsTable.openingBalance]
*/
```

- [ ] **Step 3: 请求体扩展（AccountRoutes.kt）**

```kotlin
@Serializable
data class CreateAccountRequest(val name: String, val iconColor: String, val balance: Double, val type: String = "OTHER", val isLiability: Boolean = false, val openingBalance: Double = 0.0)

@Serializable
data class UpdateAccountRequest(val name: String? = null, val iconColor: String? = null, val balance: Double? = null, val type: String? = null, val isLiability: Boolean? = null, val openingBalance: Double? = null)
```

- [ ] **Step 4: createAccount/renameAccount 透传新字段（BillService.kt）**

`createAccount`（约 315-336）接收新字段并在插入 `AccountsTable` 时写入 `type/isLiability/openingBalance`；`renameAccount`（338-354）与 `updateAccountBalance`（547-558）按需更新。

- [ ] **Step 5: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/AccountsTable.kt server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt server/src/main/kotlin/com/example/rinklnote/server/routes/AccountRoutes.kt
git commit -m "feat(server): accounts 表/AccountDTO/请求体承载 type/isLiability/openingBalance"
```

---

### Task 4: 服务端放宽"余额不为负"守卫（负债类允许负）

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt`（`createAccount` line 317）
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/AccountRoutes.kt`（PUT lines 41-44）

**Interfaces:**
- Consumes: `isLiability` 字段（Task 3）。

- [ ] **Step 1: createAccount 条件放宽**

原 `require(balance >= 0 && balance.isFinite()) { "余额不能为负" }` → 仅当 `!isLiability` 时拒绝负值：

```kotlin
require((isLiability || balance >= 0) && balance.isFinite()) { "余额不能为负" }
```

- [ ] **Step 2: PUT 路由守卫放宽**

原 `if (body.balance < 0 || !body.balance.isFinite()) return@put ... "余额不能为负"` → 读取该账户的 `isLiability`，负债类允许负：

```kotlin
val account = BillService.accountFor(request.userId, id) // 取 if (account.isLiability) 放宽
if (body.balance != null) {
    val allowNegative = account.isLiability
    if ((body.balance < 0 && !allowNegative) || !body.balance.isFinite()) return@put ... "余额不能为负"
}
```

- [ ] **Step 3: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt server/src/main/kotlin/com/example/rinklnote/server/routes/AccountRoutes.kt
git commit -m "feat(server): 负债类账户允许负余额"
```

---

### Task 5: App DTO + ApiService 承载新字段

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`（`AccountDTO` 72-80、`CreateAccountRequest` 83、`UpdateAccountRequest` 86）
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`（若签名需带新字段，否则不改）

**Interfaces:**
- Consumes: `Account` 新字段（Task 1）。
- Produces: network 反序列化得到 `type/isLiability/openingBalance`。

- [ ] **Step 1: AccountDTO 加字段**

```kotlin
@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    val balance: Double,
    val type: String = "OTHER",
    @SerialName("is_liability") val isLiability: Boolean = false,
    @SerialName("opening_balance") val openingBalance: Double = 0.0,
    val iconColor: String,
    val updatedAt: Long,
    val deleted: Boolean
)
```

- [ ] **Step 2: Create/UpdateAccountRequest 加字段（默认值保证向后兼容）**

```kotlin
@Serializable
data class CreateAccountRequest(val name: String, val iconColor: String, val balance: Double, val type: String = "OTHER", @SerialName("is_liability") val isLiability: Boolean = false, @SerialName("opening_balance") val openingBalance: Double = 0.0)

@Serializable
data class UpdateAccountRequest(val name: String? = null, val iconColor: String? = null, val balance: Double? = null, val type: String? = null, @SerialName("is_liability") val isLiability: Boolean? = null, @SerialName("opening_balance") val openingBalance: Double? = null)
```

- [ ] **Step 3: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt
git commit -m "feat(app): AccountDTO/请求体承载 type/isLiability/openingBalance"
```

---

### Task 6: SyncManager 承载新字段（LWW 合并 + push/pull）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/sync/SyncManager.kt`（`syncAccounts` 约 298-351 的 4 个 copy 点；`pushOneAccount` 约 359-388）
- Test: `app/src/test/java/com/example/rinklnote/sync/SyncManagerTest.kt`

**Interfaces:**
- Consumes: `Account` 新字段 + 双端 DTO。

- [ ] **Step 1: LWW 合并带新字段**

`syncAccounts` 中 4 处 copy（按名对账、insert、update-newer）把 `type/isLiability/openingBalance` 一并写入本地 `Account`（`copy(type=..., isLiability=..., openingBalance=..., updatedAt=...)`）。

- [ ] **Step 2: push 带新字段**

`pushOneAccount`：
```kotlin
// UpdateAccountRequest(name, iconColor, balance, type, isLiability, openingBalance)
// CreateAccountRequest(name, iconColor, balance, type, isLiability, openingBalance)
```

- [ ] **Step 3: 单测（沿用现有对账用例，加断言）**

对已有 `SyncManagerTest` 的对账用例，断言合并后 `account.type/isLiability/openingBalance` 被带入。

- [ ] **Step 4: 运行测试（编译兜底）**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.sync.SyncManagerTest"`
Expected: PASS；若环境跑不了，`./gradlew :app:compileDebugUnitTestKotlin` 必须 SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/sync/SyncManager.kt app/src/test/java/com/example/rinklnote/sync/SyncManagerTest.kt
git commit -m "feat(app): SyncManager 承载 type/isLiability/openingBalance"
```

---

### Task 7: App 新建账户改为选类型（最小 UI，便于契约被端到端用到）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt`（`AddAccountDialog` 约 292-351）
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/AssetsViewModel.kt`（`AddAccount` 事件约 54-70 携带 type）

**Interfaces:**
- Consumes: `Account` 新字段。

- [ ] **Step 1: `AddAccount` 事件带 type/isLiability**

```kotlin
data class AddAccount(val name: String, val iconColor: String, val type: String, val isLiability: Boolean, val balance: Double) : AssetsEvent
```

`addAccount` 构造 `Account(..., type=type, isLiability=isLiability, openingBalance=balance, ...)`。

- [ ] **Step 2: `AddAccountDialog` 加"选类型"首步**

在名称/金额前加类型选择：一组可选 Chip（现金/银行卡/微信/支付宝/虚拟/其他/信用卡/贷款），选中即写 `type` + `isLiability`（信用卡/贷款→负债）。默认光标落在 `OTHER`。完整卡片/图标映射在计划 2 统一，这里先让 `type` 落库。

- [ ] **Step 3: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt app/src/main/java/com/example/rinklnote/ui/viewmodel/AssetsViewModel.kt
git commit -m "feat(app): 新建账户选类型，type/isLiability 落库"
```

---

## Self-Review

- **Spec 覆盖**：账户字段（T1）、迁移（T2）、端到端契约（T3/T5/T6）、负余额放宽（T4）、类型落库（T7）覆盖完成。
- **无占位符**：除 Task 7 的完整 UI 映射外，均给出精确代码；UI 卡片映射按计划明确放到计划 2。
- **类型一致**：`type="OTHER"` 默认、`isLiability` 默认 false 贯通两端；`MIGRATION_10_11` 回填与 DTO 字段名一致。
- **交付物**：此计划完成后，App/Server 双端可承载新字段，账户仍手工维护余额；下一计划在此基础上做余额推导 + 资产页 UI。
