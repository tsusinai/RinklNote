# 资产管理重构 · 计划 3：转账 TRANSFER（App + Server）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持账户间转账——`billType = "TRANSFER"` + `bills.transfer_to_account_id`，QuickAdd 抽屉加「收支/转账」切换；转账时源账户 −amount、目标账户 +amount、总资产不变；从收支统计/图表/AI 总结/QQ 机器人中全部过滤 TRANSFER。

**Architecture:** `bills` 加可空 `transfer_to_account_id`（Room `v11→v12`，服务端 Exposed 自动加列）；`billType` 由二元变三元。App 侧 QuickAdd 转账模式、余额派生扩展为"源−/目标+"；服务端放宽 TRANSFER 校验并在账单写路径重算账户余额（覆盖 QQ/Web）。统计/图表/AI/QQ 逐处排除 TRANSFER。

**Tech Stack:** Kotlin/Compose/Room；Ktor/Exposed；无需新依赖。

## Global Constraints

- `billType` 取值：`EXPENSE` / `INCOME` / `TRANSFER`（三元）。
- 账户余额派生扩展：**TRANSFER 的源账户按 `account_id` 记 `−amount`，目标账户按 `transfer_to_account_id` 记 `+amount`**；正常收支按 `signedAmount`。`净资产` 不受转账影响（系统内移动）。
- **必须排除 TRANSFER 的消费者**：本月/日收支合计、图表、月度汇总、AI 总结、异常检测、QQ 机器人问账、`web/index.html`。凡「只过滤 `billType == 'EXPENSE'`」处**天然已排除**；凡「`if EXPENSE then A else B`」二元处**必须补 TRANSFER 分支**，否则会被当成收入。
- 转出账户 ≠ 转入账户；两者都须属于当前用户。
- **验证**：`export GRADLE_USER_HOME=D:/gradle && ./gradlew ...`；纯逻辑用 `:app:testDebugUnitTest` / `:server:test`，跑不了则编译兜底。
- **提交卫生**：精确路径暂存，禁 `git add -A`；跳 `server/build`、`.claude/settings.local.json`、`.idea`；带 `app/schemas`。

## Task 0（前置事实清单，已核查）— billType 三元化全触点

以下为已核实的 `billType` 精确分支位（`.kt` 行号可能因后续改动偏移，按文件定位）：

**App 需改（二元分支 → 补 TRANSFER）**：`ui/viewmodel/BookkeepingViewModel.kt:75`；`ui/viewmodel/QuickAddViewModel.kt:51,171-183`；`ui/screen/bookkeeping/BookkeepingScreen.kt:165`（按日合计）；`ui/screen/bookkeeping/MonthDetailOverlay.kt:328`；`ui/screen/bookkeeping/BillEditOverlay.kt:89,123,127,220`；`ui/component/BillCard.kt:111,156`；`ui/component/NumericKeypad.kt:32,44,92`；`ui/screen/quickadd/QuickAddDrawer.kt:584`。

**App 天然排除（`== "EXPENSE"` 过滤，无需改）**：`BillDao.getTotalExpense/Income`；`MonthlyChart.kt:37,40`（buildMonthDetail）；`BookkeepingScreen.computeMonthHeatmap:435`；`MonthDetailOverlay:79-88`；`domain/MonthlyChart.kt:11-25`。

**Server 需改（校验）**：`services/BillService.kt:150`（createWebBill require）、`routes/BillRoutes.kt:124`（PUT require）。

**Server 天然排除（`bill_type eq "EXPENSE"/"INCOME"`）**：`BillService.monthlyStats:487,496,505`；`insight/InsightService.kt:261,263,309,314,349-351,412,468,475`；`QQIntentRouter.kt:99,104-139`（走 monthlyStats）。

**同步/DTO 需带新字段**：`app data/network/dto/DTOs.kt BillDTO/CreateBillRequest`；`sync/SyncManager.kt Bill.toRequest:393`；`server services/BillService.kt BillDTO:14`、`routes/BillRoutes.kt CreateBillRequest:21`。

---

### Task 1: Bill 实体加 transferToAccountId + Room 迁移 v11→12 + DTO

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/entity/Bill.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt`（`version = 12` + `MIGRATION_11_12`）
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`（`BillDTO`/`CreateBillRequest`）
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/tables/BillsTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt`（`BillDTO`）
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt`（`CreateBillRequest`）

**Interfaces:**
- Produces: `Bill.transferToAccountId: Long?`；`BillDTO.transferToAccountId: Long?`；`CreateBillRequest.transferToAccountId: Long?`；`MIGRATION_11_12`。

- [ ] **Step 1: Bill 实体加列**

```kotlin
@ColumnInfo(name = "transfer_to_account_id") val transferToAccountId: Long? = null,
```

- [ ] **Step 2: Room 迁移**

```kotlin
@Database(..., version = 12, exportSchema = true)
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN transfer_to_account_id INTEGER DEFAULT NULL")
    }
}
// .addMigrations(..., MIGRATION_10_11, MIGRATION_11_12)
```

- [ ] **Step 3: 双端 DTO/请求体加 `transferToAccountId`**：`BillDTO`（`@SerialName("transfer_to_account_id")`）、`CreateBillRequest`、server `BillDTO`/`CreateBillRequest`、server `BillsTable.transferToAccountId`（`long("transfer_to_account_id").nullable()`）。

- [ ] **Step 4: 编译验证（两端）**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin :server:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/db/entity/Bill.kt app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt app/schemas server/src/main/kotlin/com/example/rinklnote/server/tables/BillsTable.kt server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt
git commit -m "feat(app,server): bills 加 transfer_to_account_id + 双端 DTO/迁移"
```

---

### Task 2: 按账户派生余额扩展转账双腿（源−/目标+）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/BillDao.kt`（`getBalanceSum`）
- Modify: `app/src/main/java/com/example/rinklnote/domain/AssetMath.kt`（如需带 `transferToAccountId` 的 signed 助手）
- Test: `app/src/test/java/com/example/rinklnote/domain/AssetMathTest.kt`

**Interfaces:**
- Consumes: Plan 2 的 `recomputeBalance`。
- Produces: 覆盖转账双腿的 `getBalanceSum`。

- [ ] **Step 1: 改 `getBalanceSum` 覆盖源/目标双腿**

```kotlin
@Query("SELECT CAST(SUM(" +
  "CASE WHEN bill_type='TRANSFER' AND account_id=:accountId THEN -amount" +
  "     WHEN bill_type='TRANSFER' AND transfer_to_account_id=:accountId THEN +amount" +
  "     WHEN bill_type='INCOME' THEN amount" +
  "     WHEN bill_type='EXPENSE' THEN -amount" +
  "     ELSE 0 END) AS REAL) " +
  "FROM bills WHERE deleted = 0 AND (account_id = :accountId OR transfer_to_account_id = :accountId)")
suspend fun getBalanceSum(accountId: Long): Double
```

- [ ] **Step 2: 单测补转账双腿**

```kotlin
@Test fun transferMovesValueBetweenAccounts() {
    // 转账：source 100 → target 300，金额 40
    val source = balanceAfterTransfer(sourceBalance = 100.0, targetBalance = 300.0, amount = 40.0)
    assertEquals(60.0, source.first, 1e-9)   // 源 -40
    assertEquals(340.0, source.second, 1e-9) // 目标 +40
}
```

（在有 Room 的环境用集成测试；纯逻辑可单测 `signedTransferFor(sourceOrTarget)` 辅助函数：源返回 `-amount`，目标返回 `+amount`。）

- [ ] **Step 3: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/db/dao/BillDao.kt app/src/main/java/com/example/rinklnote/domain/AssetMath.kt app/src/test/java/com/example/rinklnote/domain/AssetMathTest.kt
git commit -m "feat(app): 按账户余额派生支持转账双腿(源−/目标+)"
```

---

### Task 3: QuickAdd 抽屉「收支/转账」切换 + 转账表单 + 余额增量

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt`

**Interfaces:**
- Consumes: `adjustAccountBalance`、`signedAmount`、`getById`.
- Produces: 转账模式；转账落一张 `billType="TRANSFER"` 的账单并 `adjust(source, -amount)` + `adjust(target, +amount)`。

- [ ] **Step 1: QuickAdd 加转账模式状态**

`QuickAddState` 加 `mode: QuickAddMode`（`EXPENSE`/`INCOME`/`TRANSFER`），`transferFromId`/`transferToId`。`toggleType` 仅在收支间切；新增 `switchToTransfer()` / `switchToIo()`。

- [ ] **Step 2: 抽屉顶部 SegmentedToggle**

在现有「收支」切换旁加「收支 / 转账」分段；切到 TRANSFER 时表单变为：转出账户下拉（`SelectAccount`）→ 转入账户下拉 → 金额（NumericKeypad）→ 备注。复用账户选择与键盘。

- [ ] **Step 3: finalConfirm 转账分支**

```kotlin
if (state.mode == TRANSFER) {
    val transfer = buildTransferBill(fromId, toId, amount, remark) // billType="TRANSFER", transferToAccountId=toId, accountId=fromId
    repository.insertBill(transfer)
    repository.adjustAccountBalance(fromId, -amount)
    repository.adjustAccountBalance(toId, +amount)
    push...
} else { /* 既有收支 */ }
```

`buildTransferBill` 设 `billType="TRANSFER"`、`accountId=fromId`、`transferToAccountId=toId`、`amount`。禁止 `fromId==toId`。

- [ ] **Step 4: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt
git commit -m "feat(app): QuickAdd 收支/转账切换 + 转账表单 + 双边余额联动"
```

---

### Task 4: App 端 TRANSFER 在二元分支中补分支/中性化

**Files:** Enumerated in Task 0（对照清单逐处改）
- `ui/screen/bookkeeping/BookkeepingScreen.kt:165`、`ui/component/BillCard.kt:111,156`、`ui/screen/bookkeeping/MonthDetailOverlay.kt:328`、`ui/screen/bookkeeping/BillEditOverlay.kt:89,123,127,220`、`ui/component/NumericKeypad.kt:32,44,92`、`ui/viewmodel/BookkeepingViewModel.kt:75`、`ui/viewmodel/QuickAddViewModel.kt:51,171-183`

**Interfaces:**
- 目标：TRANSFER 不再被当成收入/支出，入账为"转账"中性。

- [ ] **Step 1: 常量集中化**

在 `ui/util/AccountType.kt` 或 `data/db/entity/Bill.kt` 加 `const val BILL_TYPE_TRANSFER = "TRANSFER"`，替换字面量。

- [ ] **Step 2: 逐处按类型改**

- `BookkeepingScreen.kt:165` 按日合计：`if (billType==BILL_TYPE_TRANSFER) 0 else (if EXPENSE -amount else +amount)`。
- `BillCard.kt:111,156`：`isExpense`/`isIncome` 增加转移判定；TRANSFER 渲染中性色 + 副标签「转账」。
- `MonthDetailOverlay.kt:328` 行类型：TRANSFER → `isTransfer`，不归入支出/收入色。
- `NumbericKeypad/BillEditOverlay`：TRANSFER 不展示收支切换；`BookkeepingViewModel.kt:75` 编辑转移账单时不拉分类（因转移无分类字段），走转移编辑入口。
- `QuickAddViewModel.kt:51` 仅在 `EXPENSE/INCOME` 时取分类。

- [ ] **Step 3: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt app/src/main/java/com/example/rinklnote/ui/component/BillCard.kt app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/MonthDetailOverlay.kt app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BillEditOverlay.kt app/src/main/java/com/example/rinklnote/ui/component/NumericKeypad.kt app/src/main/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModel.kt app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt app/src/main/java/com/example/rinklnote/data/db/entity/Bill.kt
git commit -m "feat(app): billType 三元化，TRANSFER 各处中性化/排除"
```

---

### Task 5: Server 端 TRANSFER 校验 + 账单写路径重算账户余额（覆盖 QQ/Web）

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt`（`createWebBill:150`、`createBill:93-109`、`monthlyStats`、新增 `recomputeAccountBalance`）
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt`（PUT:124）

**Interfaces:**
- Consumes: `AccountsTable.type/isLiability/openingBalance`。

- [ ] **Step 1: 校验允许 TRANSFER + 目标账户归属**

`createWebBill`/`BillRoutes PUT` 的 `require(billType == EXPENSE || INCOME)` → 追加 `|| billType == "TRANSFER"`。转账时校验 `transferToAccountId` 属于该用户且 `!= accountId`。

- [ ] **Step 2: 服务端重算账户余额 helper（QQ/Web 记账后保持余额正确）**

```kotlin
suspend fun recomputeAccountBalance(accountId: Long) {
    val opening = AccountsTable.select { AccountsTable.id eq accountId }.firstOrNull()?.get(AccountsTable.openingBalance) ?: return
    val sum = Transactions.select {
        (BillsTable.accountId eq accountId).or(BillsTable.transferToAccountId eq accountId)
    }.sumOf {
        when {
            it[BillsTable.billType] == "TRANSFER" && it[BillsTable.accountId] == accountId -> -it[BillsTable.amount]
            it[BillsTable.billType] == "TRANSFER" && it[BillsTable.transferToAccountId] == accountId -> it[BillsTable.amount]
            it[BillsTable.billType] == "INCOME" -> it[BillsTable.amount]
            it[BillsTable.billType] == "EXPENSE" -> -it[BillsTable.amount]
            else -> 0.0
        }
    }
    AccountsTable.update({ AccountsTable.id eq accountId }) { it[balance] = opening + sum }
}
```

在 `createBill`/`createWebBill`/`update`/`delete`（软删）后调用，刷新受影响的源/目标账户。

- [ ] **Step 3: 服务端单测**

`server/src/test/.../BillServiceTest.kt` 补用例：转账创建后源账户 balance −amount、目标 +amount；`monthlyStats` 不含 TRANSFER。

- [ ] **Step 4: 编译 + 测试**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin :server:test`
Expected: BUILD SUCCESSFUL / 用例 PASS；跑不了则 `:server:compileKotlin` 兜底。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt server/src/test
git commit -m "feat(server): 支持 TRANSFER 校验并在账单写路径重算账户余额"
```

---

### Task 6: AI 总结 / 异常 / QQ 机器人逐处排除 TRANSFER + 回归

**Files:**
- `server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt`（`computeMonthlyFacts:261,263`、`anomalyCheck:309,314`、`naturalQuery:349-351`、`suggestDailyPattern:412`、`habitReminder:468,475`）— 逐处确认 `billType == "EXPENSE"/"INCOME"` 过滤器排除 TRANSFER；凡把非 EXPENSE 当 INCOME 的二元处补分支。
- `server/src/main/kotlin/com/example/rinklnote/server/services/QQIntentRouter.kt:99,104-139` — 走 `monthlyStats`，天然排除，回归确认。
- `web/index.html` — 转账记录渲染为「转账」，不进入收支合计与图表。

**Interfaces:**
- 目标：AI 总结/异常/Q 机器人/Web 均把 TRANSFER 当"系统内移动"，不计入收入/支出。

- [ ] **Step 1: 逐处确认/补分支**：凡把 `!= "EXPENSE"` 归为收入的逻辑补 `&& billType != "TRANSFER"`。

- [ ] **Step 2: 回归**：`:server:test` 中 `InsightServiceTest`/`QQIntentRouterTest` 补一个含 TRANSFER 的用例，断言不进入收入/支出事实与问账金额。

- [ ] **Step 3: 编译 + 测试**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin :server:test`
Expected: PASS / 编译兜底。

- [ ] **Step 4: 提交**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt server/src/main/kotlin/com/example/rinklnote/server/services/QQIntentRouter.kt server/src/test web/index.html
git commit -m "feat(server,web): AI 总结/异常/QQ/Web 排除 TRANSFER"
```

---

## Self-Review

- **Spec 覆盖**：数据契约（T1）、派生双腿（T2）、QuickAdd 转账（T3）、App 三元分支（T4）、服务端校验+重算（T5）、AI/QQ/Web 排除+回归（T6）覆盖完成。
- **无占位符**：SQL/逻辑/事件签名均给出；仅枚举清单按 Task 0 的精确文件与行号落实。
- **类型一致**：`BILL_TYPE_TRANSFER` 常量、`transferToAccountId` 字段双端一致；`getBalanceSum` 与服务端 `recomputeAccountBalance` 对双腿的符号约定一致（源 −，目标 +）。
- **风险控制**：天然排除点与需改点已明确区分，避免误把二元分支当天然排除。

---

## 三计划顺序与交付物

| 计划 | 交付物 | 独立可验证 |
|---|---|---|
| 1 · 账户模型+数据契约 | types/负债/期初双端承载、迁移、负余额放宽 | `:app:compileDebugKotlin` + `:server:compileKotlin` |
| 2 · 余额推导+资产页 UI | 余额跟随账单、净资产、资产/负债分区、期初编辑/对账重算 | `AssetMathTest` + 编译 |
| 3 · 转账 TRANSFER | 转账模式、源−/目标+、统计/AI/QQ/Web 排除 | `BillServiceTest`/`InsightServiceTest` + 编译 |

建议按 1→2→3 顺序执行；每计划各自可提交（commit 边界），便于审阅与回滚。
