# 预算功能升级 Implementation Plan（M1 分类预算主线）

> **For Claude:** Use `${SUPERPOWERS_SKILLS_ROOT}/skills/collaboration/executing-plans/SKILL.md` to implement this plan task-by-task.

**Goal:** 把预算从「每月一个总额」升级为「总额 + 一级分类 + 子分类」三层预算，每层独立进度/超支判定，并展示上月结余（仅展示不结转）。

**Architecture:** 服务端 `budgets` 表增加 `category_id` / `sub_category_id` / `period_type` 三列（Exposed `createMissingTablesAndColumns` 自动加列），新增 `/api/budgets/summary` 快照接口一次返回总额/分类/子分类/上月结余；App 端 Room 数据库 10→11 写正式迁移保数据，`BudgetViewModel` 合流「预算列表 + 本月账单」按分类/子分类分组派生进度，`PlanScreen` 升级为分层预算 UI。同步协议 payload 同步加字段。M2（年度预算+等分）、M3（提醒推送）见文末路线图，另立计划。

**Tech Stack:** Kotlin 2.0.21 / Ktor + Exposed（服务端）、Jetpack Compose + Material3 + Room 2.6.1（App）、kotlinx-serialization、Retrofit、JUnit 4。

---

## 设计决策（已与用户确认）

- 总额预算独立；分类预算「可设可不设」；子分类预算可设，支出自然归集：子分类支出计入一级分类、一级分类支出计入总额
- 周期一期只做「月」，表预留 `period_type` 字段（`MONTHLY`），年度/周/自定义二期实现
- 结转=仅展示：上月结余 = 上月预算 − 上月支出，不改变本月预算额
- 提醒（QQ 即时推送 50/80/100 + App 本地通知 + 日报摘要）属于 M3，本计划不实现
- 子分类支出按「bill.categoryId == 子分类预算.parentCategoryId && bill.subCategoryName == 子分类名」名称归集（口径说明）：App 写账单时须保证 subCategoryName 与 sub_categories.name 严格一致；改名不影响历史账单归集，属已知数据模型限制（中长期可给 bills 补 sub_category_id）
- 时间区：记账日界统一用 `Asia/Shanghai`（App 端已有 `bookkeepingZone()`；服务端同理）

## 环境与验证约定（重要）

- 本机默认 JDK 是 24，Gradle 8.13 建 Test 任务会报 `Type T not present`。**所有 gradle 命令必须**：
  ```powershell
  $env:JAVA_HOME="D:\Codes\AndroidStudio\jbr"   # JDK 21
  $env:GRADLE_USER_HOME="D:\Codes\RinklNote\.gradle-home"
  ```
- `:server:test` 需要联 Maven 下载 junit 依赖：在**非沙箱（提权）环境**先 `.\gradlew.bat --stop` 停掉沙箱内启动的守护进程，再跑测试，否则网络被拒
- `settings.gradle.kts` 保持本地未提交改动 `// include(":app")`（当前环境 :app 的 AGP 插件在本机 CLI 编译失败）。服务端用 CLI 编译/测试；**App 用 Android Studio（D:\Codes\AndroidStudio）编译验证**
- commit message 用下划线不用空格（如 `feat:budget_category_scope`）；每次 commit 用提权 `git` 命令
- 工作目录：`D:\Codes\RinklNote\.claude\worktrees\budget-upgrade`（分支 `codex/budget-upgrade`）

---

## M1 任务清单

### Task 1: 服务端 budgets 表加列（period_type / category_id / sub_category_id）

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/tables/BudgetsTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt`

**Step 1: 改表定义**
在 `BudgetsTable` 的 `monthStart` 后加：
```kotlin
val periodType = varchar("period_type", 10).default("MONTHLY") // MONTHLY | YEARLY(二期)
val categoryId = long("category_id").references(CategoriesTable.id).nullable()
val subCategoryId = long("sub_category_id").references(SubCategoriesTable.id).nullable()
```
删除 `init { uniqueIndex("uq_budgets_user_month", ...) }`（若存在）—— 该唯一索引在服务端 `runMigrations()` 里创建，见下一步。

**Step 2: 迁移索引**
`Database.kt` 的 `runMigrations()` 加：
```kotlin
// budget 升级：旧的 (user_id, month_start) 唯一索引会阻止同月多条（总额+分类预算）
try { exec("DROP INDEX IF EXISTS uq_budgets_user_month") } catch (_: Exception) {}
exec("CREATE INDEX IF NOT EXISTS idx_budgets_user_month ON budgets(user_id, month_start)")
```
（唯一性改由 `BudgetService.upsert` 服务层保证，见 Task 2。）

**Step 3: 编译验证**
```powershell
cd D:\Codes\RinklNote\.claude\worktrees\budget-upgrade
$env:JAVA_HOME="D:\Codes\AndroidStudio\jbr"; $env:GRADLE_USER_HOME="D:\Codes\RinklNote\.gradle-home"
.\gradlew.bat :server:compileKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**
```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/BudgetsTable.kt server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt
git commit -m "feat:budget_schema_category_scope"
```

---

### Task 2: 服务端 DTO + BudgetService 支持分类/子分类维度

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BudgetService.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BudgetRoutes.kt`

**Step 1: 扩展 DTO**
```kotlin
@Serializable
data class BudgetDTO(
    val id: Long,
    val monthStart: Long,
    val amount: Double,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class UpsertBudgetRequest(
    val monthStart: Long,
    val amount: Double,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null
)
```

**Step 2: upsert 按 (userId, monthStart, categoryId, subCategoryId) 定位**
`upsert(userId, monthStart, amount, categoryId = null, subCategoryId = null, periodType = "MONTHLY")`：
- where 条件加 `and (BudgetsTable.categoryId eq categoryId)` — 注意 Exposed: nullable 列比较用 `eq categoryId`（传 null 会生成 `IS NULL`，需用 `(BudgetsTable.categoryId eq categoryId)` 的 Exposed 语义：Exposed 的 `eq` 接受 nullable 值并生成 IS NULL，OK）
- insert 时写入 periodType/categoryId/subCategoryId
- `toDTO()` 带上新字段

**Step 3: 路由透传**
`BudgetRoutes.kt` PUT 改为：
```kotlin
val body = call.receive<UpsertBudgetRequest>()
call.respond(
    budgetService.upsert(userId, body.monthStart, body.amount, body.categoryId, body.subCategoryId, body.periodType)
)
```

**Step 4: 编译**
同上命令，Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**
```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/BudgetService.kt server/src/main/kotlin/com/example/rinklnote/server/routes/BudgetRoutes.kt
git commit -m "feat:budget_upsert_category_scope"
```

---

### Task 3: 服务端 summary 接口（总额/分类/子分类/上月结余）

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BudgetService.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BudgetRoutes.kt`

**Step 1: 定义响应模型（BudgetService.kt 内）**
```kotlin
@Serializable
data class BudgetSummaryDTO(
    val periodStart: Long,
    val totalBudget: BudgetDTO? = null,
    val totalExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetDTO> = emptyList(),
    val subCategoryBudgets: List<SubCategoryBudgetDTO> = emptyList(),
    val lastMonthSurplus: Double? = null // 上月预算-上月支出；上月无预算时为 null
)

@Serializable
data class CategoryBudgetDTO(
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,          // 0 = 未设预算
    val expense: Double
)

@Serializable
data class SubCategoryBudgetDTO(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amount: Double,
    val expense: Double
)
```

**Step 2: 实现 `fun summary(userId: Long, periodStart: Long): BudgetSummaryDTO`**
- `periodStart` 归一化：不足月首日时取当月 1 日 00:00（当地时区 Asia/Shanghai）；`nextStart = periodStart.plusMonths(1)`（用 `java.time.ZonedDateTime`，zone `Asia/Shanghai`）
- 本月支出：`BillsTable` where `userId eq userId and billType eq "EXPENSE" and deleted eq false and date ge periodStart and date lt nextStart`，按 `categoryId` 求和；再按 `(categoryId, subCategoryName)` 求和
- 预算行：`list(userId)` 中 `periodType == "MONTHLY" && monthStart == periodStart && deleted == false`
  - `categoryId == null && subCategoryId == null` → totalBudget
  - `categoryId != null && subCategoryId == null` → categoryBudgets（categoryName 从 `CategoriesTable` 查）
  - `subCategoryId != null` → subCategoryBudgets（name/parentCategoryId 从 `SubCategoriesTable` 查；expense 用「bills.categoryId == parentCategoryId && bills.subCategoryName == name」匹配）
- 上月：`prevStart = periodStart.minusMonths(1)`；prevBudget 若存在：`surplus = prevAmount - prevExpense`；否则 null

**Step 3: 路由**
```kotlin
get("/summary") {
    val userId = ...(同 GET 鉴权)
    val periodStart = call.request.queryParameters["periodStart"]?.toLongOrNull() ?: getMonthStartMillis()
    call.respond(budgetService.summary(userId, periodStart))
}
```
注意放 `route("/api/budgets")` 内、`get { }` 之前（Routing 匹配顺序：具体路径优先声明）。

**Step 4: 测试先行（TestDatabase 模式参考 `server/src/test/.../BudgetServiceTest.kt`）**
新增测试用例（先写、确认失败/或先实现再补）：
1. upsert 总额 + 同月分类预算共存，list 返回两条且字段正确
2. upsert 同 (user,month,category) 二次调用为更新而非新增
3. summary：造 1 笔总额预算 + 1 笔分类预算 + 2 笔账单（1 笔带 subCategoryName），断言 totalExpense / categoryBudgets / subCategoryBudgets / expense 归集
4. summary 上月结余：上月预算 1000、上月支出 600 → surplus 400；无上月预算 → null

运行：`.\gradlew.bat :server:test --console=plain`（先 `--stop` + 提权联网一次）
Expected: `BUILD SUCCESSFUL`，所有测试通过

**Step 5: Commit**
```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/BudgetService.kt server/src/main/kotlin/com/example/rinklnote/server/routes/BudgetRoutes.kt server/src/test/kotlin/com/example/rinklnote/server/services/BudgetServiceTest.kt
git commit -m "feat:budget_summary_endpoint"
```

---

### Task 4: App Room 迁移 10→11 + Budget 实体

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/entity/Budget.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt`

**Step 1: 实体加字段**
```kotlin
@ColumnInfo(name = "period_type", defaultValue = "MONTHLY") val periodType: String = "MONTHLY",
@ColumnInfo(name = "category_id") val categoryId: Long? = null,
@ColumnInfo(name = "sub_category_id") val subCategoryId: Long? = null,
```
（插在 `monthStart` 与 `amount` 之间，字段顺序不影响）

**Step 2: 迁移 10→11**
```kotlin
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE budgets ADD COLUMN period_type TEXT NOT NULL DEFAULT 'MONTHLY'")
        db.execSQL("ALTER TABLE budgets ADD COLUMN category_id INTEGER")
        db.execSQL("ALTER TABLE budgets ADD COLUMN sub_category_id INTEGER")
    }
}
```
- `@Database(version = 11)`，`addMigrations(...)` 追加 `MIGRATION_10_11`
- 若 Room 校验报 `defaultValue` 不匹配：确认实体 `@ColumnInfo(defaultValue = "MONTHLY")` 已加（已加则不会）
- **保留 `fallbackToDestructiveMigration()` 不变**（本迁移覆盖后正常用户不会触发 fallback）

**Step 3: 验证**
- 用 Android Studio 编译 `:app`（D:\Codes\AndroidStudio 打开本 worktree，`Build > Make Project`）
- 迁移保数据：装到有旧库的设备/模拟器上启动一次，确认账单还在、预算原有金额还在
- 若当前环境无法跑 :app（AGP 插件问题无解），至少编译通过并人工核对 SQL 与实体一致

**Step 4: Commit**
```bash
git add app/src/main/java/com/example/rinklnote/data/db/entity/Budget.kt app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt
git commit -m "feat:app_budget_db_v11_migration"
```

---

### Task 5: App 网络 DTO + SyncManager 同步新字段

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`
- Modify: `app/src/main/java/com/example/rinklnote/sync/SyncManager.kt`

**Step 1: DTO 对齐服务端**
`BudgetDTO` / `UpsertBudgetRequest` 加 `periodType`/`categoryId`/`subCategoryId`（默认值与服务端一致）。

**Step 2: ApiService 加 summary**
```kotlin
@GET("api/budgets/summary")
suspend fun getBudgetSummary(@Query("periodStart") periodStart: Long): BudgetSummaryDTO
```
（`BudgetSummaryDTO` 及嵌套模型加到 DTOs.kt，字段与服务端 `BudgetSummaryDTO` 一致，`ignoreUnknownKeys` 兜底）

**Step 3: SyncManager.syncBudgets 同步新字段**
- push：`UpsertBudgetRequest(monthStart = b.monthStart, amount = b.amount, periodType = b.periodType, categoryId = b.categoryId, subCategoryId = b.subCategoryId)`
- pull LWW 构造 `Budget(...)` 时带上 `periodType = dto.periodType, categoryId = dto.categoryId, subCategoryId = dto.subCategoryId`
- `pushBudget(budget)`（BudgetViewModel 直接调用）同样带新字段

**Step 4: 编译（Android Studio）+ Commit**
```bash
git add app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt app/src/main/java/com/example/rinklnote/data/network/ApiService.kt app/src/main/java/com/example/rinklnote/sync/SyncManager.kt
git commit -m "feat:app_budget_sync_category_scope"
```

---

### Task 6: BudgetViewModel 分层状态 + 派生逻辑（纯逻辑先行）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/BudgetViewModel.kt`
- Create: `app/src/test/java/com/example/rinklnote/ui/viewmodel/BudgetViewModelTest.kt`

**Step 1: 新 BudgetState**
```kotlin
data class CategoryBudgetState(
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,   // 0 = 未设
    val expense: Double,
    val subBudgets: List<SubCategoryBudgetState> = emptyList()
) {
    val progress: Float get() = if (amount > 0) (expense / amount).toFloat().coerceIn(0f, 1f) else 0f
    val isOverBudget: Boolean get() = amount > 0 && expense > amount
}

data class SubCategoryBudgetState(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amount: Double,
    val expense: Double
) {
    val progress: Float get() = if (amount > 0) (expense / amount).toFloat().coerceIn(0f, 1f) else 0f
    val isOverBudget: Boolean get() = amount > 0 && expense > amount
}

data class BudgetState(
    val totalBudget: Budget? = null,
    val monthExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetState> = emptyList(),
    val lastMonthSurplus: Double? = null,
    val monthStart: Long = getMonthStart(),
    val isLoading: Boolean = false
) {
    val totalProgress: Float get() = if (totalBudget != null && totalBudget.amount > 0) (monthExpense / totalBudget.amount).toFloat().coerceIn(0f, 1f) else 0f
    val isOverTotal: Boolean get() = totalBudget != null && totalBudget.amount > 0 && monthExpense > totalBudget.amount
}
```

**Step 2: ViewModel 合流**
- `observeBudgets()` 收集后：`periodType == "MONTHLY" && monthStart == 本月`，分出 total（categoryId==null && subCategoryId==null）/ 分类 / 子分类
- `observeBillsByMonth(...)` 收集后：expense 按 `bill.categoryId` 求和、按 `(categoryId, subCategoryName)` 求和（子分类名直接来自账单）
- 分类预算 UI 行需要「该分类下未设子预算时也显示」→ 用 `repository.expenseCategories`（StateFlow）补全本月有支出/有预算的分类集合
- `lastMonthSurplus`：本地可见期先用「本月启动时查一次」（`getBudget(prevStart)` + `getTotalExpense(prevStart, curStart)`），后续可切 summary 接口（Task 7）

**Step 3: Event 扩展**
```kotlin
sealed interface BudgetEvent {
    data class SetBudget(val amount: Double, val categoryId: Long? = null, val subCategoryId: Long? = null) : BudgetEvent
}
```
`setBudget` 按 `(monthStart, categoryId, subCategoryId)` upsert：本地有行则 update，无则新建 `Budget(monthStart, amount, periodType="MONTHLY", categoryId, subCategoryId, dirty=true)`。

**Step 4: 单元测试（BudgetViewModelTest.kt，JUnit4 + kotlinx-coroutines-test）**
用 fake BillRepository 注入固定预算/账单列表，断言：
1. 总额 + 分类 + 子分类三层 progress 正确
2. 子分类支出归集到分类，再归集到总额
3. isOverTotal / isOverBudget 边界（== 不超，> 超）
4. SetBudget(categoryId=X) 后 state.categoryBudgets 出现对应行
运行：Android Studio 里跑 `BudgetViewModelTest`（当前环境 :app 无法 CLI 编译）

**Step 5: Commit**
```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/BudgetViewModel.kt app/src/test/java/com/example/rinklnote/ui/viewmodel/BudgetViewModelTest.kt
git commit -m "feat:budget_viewmodel_category_scope"
```

---

### Task 7: PlanScreen 分层预算 UI

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/plan/PlanScreen.kt`

**Step 1: UI 结构（沿用现有卡片 + NumericKeypad 交互模式）**
- 顶部：总额预算卡片（进度条 = totalProgress，超支红，剩余天数）
- 上月结余行：`lastMonthSurplus` 非空时显示「上月结余 +¥xxx（仅展示）」
- 分类列表：每个分类行 = 分类名、金额/进度、超支标红；点击 → 输入金额键盘（SetBudget(categoryId)）；行内展开显示子分类行（同样可设/编辑）
- 未设预算的分类：显示「未设」，点击进入设置
- 键盘复用现有 `NumericKeypad` + 确认流程（参考当前实现）；编辑目标由 `remember { mutableStateOf<BudgetEditTarget?>(null) }` 记录（target = Total / Category(id) / SubCategory(id)）
- 空态：本月无任何预算时显示引导文案「点击分类设置预算」

**Step 2: 交互接线**
- `PlanScreen(viewModel: BudgetViewModel, isActive: Boolean)` 签名不变，`AppNavigation.kt` 不需改动（Factory 已传 repository + syncManager）
- BudgetViewModel.Factory 不变

**Step 3: 编译（Android Studio）+ 真机走查**
- 设总额 → 设分类 → 设子分类 → 记账 → 各层进度/超支正确
- 上月结余显示正确

**Step 4: Commit**
```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/plan/PlanScreen.kt
git commit -m "feat:plan_screen_category_budgets"
```

---

### Task 8: 端到端联调（服务端部署）

**Step 1: 服务端编译打包部署（参考 docs/技术文档.md:168）**
```powershell
# 提权环境（需联网/ssh/scp）
$env:JAVA_HOME="D:\Codes\AndroidStudio\jbr"; $env:GRADLE_USER_HOME="D:\Codes\RinklNote\.gradle-home"
.\gradlew.bat :server:installDist --console=plain
tar -czf rinklnote-server.tar.gz -C server/build/install server
scp rinklnote-server.tar.gz jmbot:/tmp/
ssh jmbot "sudo systemctl stop rinklnote; tar -xzf /tmp/rinklnote-server.tar.gz -C /home/admin; chmod +x /home/admin/server/bin/server; sudo systemctl start rinklnote; sleep 2; sudo systemctl is-active rinklnote"
```
Expected: `active`；`sudo journalctl -u rinklnote -n 20` 确认启动无异常
注意：服务端 `createMissingTablesAndColumns` 会自动给现有 Postgres `budgets` 表加列；`runMigrations()` 会 drop 旧的 `uq_budgets_user_month` 唯一索引（历史单条月度预算不受影响）

**Step 2: 联调**
- App 真机升级（保留旧库）→ 原有预算还在
- 设分类预算 → App 重启 → 数据还在；开飞行模式记账再联网 → 同步后服务端有对应 budgets 行（含 category_id）
- QQ bot 发「预算」类查询语句 → 走现有 router（本 M1 不新增 QQ 预算语句，若报错记录到 M3）

**Step 3: 合并准备**
按 `finishing-a-development-branch` skill 处理分支合并（先与 main 同步、确认无冲突再合并）。

---

## M2/M3 路线图（另立计划）

- **M2 年度预算**：`period_type=YEARLY` 行 + 「年度总额 ÷12 等分到月」的派生视图（不改月度行）；年度设置 UI + 年度汇总视图；预留 W 周期不做
- **M3 提醒**：服务端账单落库钩子检查 50/80/100（总额/分类/子分类各层），PushScheduler 加 `BUDGET_ALERT` 类型、去重键 `(user, day, level)`；App 本地记一笔超阈值即时通知；每日日报加一行预算摘要；QQ 预算查询语句补 summary 用法
*** End Patch

---

## 基线更新说明（已合并 main 19dd6c1：App repository/domain 重构 + QQ 语音修复）

- `BudgetRepository` + `BudgetRepositoryImpl` 已存在（New）：封装 BudgetDao，原 `BillRepository` 中的预算方法已移除
  - `observeBudgets(): Flow<List<Budget>>` / `getBudget(monthStart): Budget?` / `upsertBudget` / `getUnsyncedBudgets` / `markBudgetSynced` / `deleteBudgetByServerId`
- `BudgetViewModel` 构造签名已变为 `(BillRepository, BudgetRepository, SyncManager?)`（AppNavigation 已相应接线）
- `BillType` / `Source` / `MessageKind` 已抽为 `domain` 枚举；`Bill.billType` 类型为 `BillType`（过滤用 `BillType.EXPENSE`）
- `SyncManager.syncBudgets()` / `pushBudget()` 仍直接使用 `BudgetDao` + `UpsertBudgetRequest(monthStart, amount)`（pushBudget 位于 ~229 行）
- `AppDatabase` version 10 未变（重构只加了 TypeConverters 等），本计划的迁移仍为 **10→11**
- 本计划 Task 5/6/7 涉及上述文件时以本说明为准；Task 1-4、8 不受影响
*** End Patch
