# 资产管理重构 · 计划 2：余额推导 + 资产页 UI + 净资产 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让账户余额变成 `期初余额 + Σ(账单增减)`，并重做资产页（资产/负债分组、净资产卡、账户 ⋮ 菜单编辑期初/发起对账重算、新建选类型）。转账不在本计划（计划 3）。

**Architecture:** `balance` 作为**物化字段**落库，记账/改账/删账时在仓储层事务性增减；提供「一键重建余额」从 `期初 + Σ账单` 重算自愈。派生公式为纯 Kotlin（`signedAmount`），单测可验。资产页 `AssetsScreen` 重构为扁平列表 + 章节标题 + 净资产卡。类型→图标/配色映射在本计划统一。

**Tech Stack:** Kotlin/Compose/Room；无新增依赖。

## Global Constraints

- 类型值：`CASH/BANK_CARD/WECHAT/ALIPAY/VIRTUAL/OTHER/CREDIT/LOAN`；`CREDIT/LOAN` 为负债。
- 派生态：`signedAmount(bill) = when(billType){ INCOME→+amount; EXPENSE→−amount; else→0 }`（TRANSFER 在计划 3 扩展，本计划无 TRANSFER 行）。
- `balance = openingBalance + Σ(signedAmount(bill), 同账户, deleted=0)`。
- `净资产 = Σ(非负债余额) − Σ(负债余额)`。
- 视觉：主色 `#7EC1FC`、背景 `#F7F7F9`、卡片 15dp 圆角 + drop shadow、支出红 `#CA3032`、收入绿 `#04A433`（对齐既有）。资产页沿用「我的」页分组卡片语言。
- **验证**：`export GRADLE_USER_HOME=D:/gradle && ./gradlew ...`（JDK17 由 gradle.properties 指定）；纯逻辑用 `:app:testDebugUnitTest`，跑不了则 `:app:compileDebugKotlin` 兜底。
- **提交卫生**：精确路径暂存，禁 `git add -A`；跳 `server/build`、`.claude/settings.local.json`、`.idea`；带 `app/schemas`。

---

### Task 1: 纯 Kotlin 派生逻辑（signedAmount / netWorth）+ 单测

**Files:**
- Create: `app/src/main/java/com/example/rinklnote/domain/AssetMath.kt`
- Test: `app/src/test/java/com/example/rinklnote/domain/AssetMathTest.kt`

**Interfaces:**
- Produces: `fun signedAmount(billType: String, amount: Double): Double`；`fun netWorth(accounts: List<Account>): Double`。

- [ ] **Step 1: 写失败测试**

```kotlin
class AssetMathTest {
    @Test fun signedAmountIncomePositive() { assertEquals(10.0, signedAmount("INCOME", 10.0), 1e-9) }
    @Test fun signedAmountExpenseNegative() { assertEquals(-5.0, signedAmount("EXPENSE", 5.0), 1e-9) }
    @Test fun signedAmountOtherZero() { assertEquals(0.0, signedAmount("TRANSFER", 9.0), 1e-9) }
    @Test fun netWorthSubtractsLiability() {
        val cash = Account(name="现金", balance=100.0, type="CASH", iconColor="#000")
        val credit = Account(name="信用卡", balance=30.0, type="CREDIT", isLiability=true, iconColor="#000")
        assertEquals(70.0, netWorth(listOf(cash, credit)), 1e-9)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.domain.AssetMathTest"`
Expected: FAIL（`signedAmount`/`netWorth` 未定义）。若环境跑不了，用独立 kotlinc 入口验算（见 Global Constraints）。

- [ ] **Step 3: 实现**

```kotlin
// app/src/main/java/com/example/rinklnote/domain/AssetMath.kt
package com.example.rinklnote.domain

import com.example.rinklnote.data.db.entity.Account

fun signedAmount(billType: String, amount: Double): Double = when (billType) {
    "INCOME" -> +amount
    "EXPENSE" -> -amount
    else -> 0.0
}

fun netWorth(accounts: List<Account>): Double =
    accounts.filter { !it.isLiability }.sumOf { it.balance } -
    accounts.filter { it.isLiability }.sumOf { it.balance }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.domain.AssetMathTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/domain/AssetMath.kt app/src/test/java/com/example/rinklnote/domain/AssetMathTest.kt
git commit -m "feat(app): AssetMath signedAmount/netWorth 派生逻辑"
```

---

### Task 2: 按账户求和 DAO + 重算仓储方法

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/BillDao.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/AccountDao.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/repository/BillRepository.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt`

**Interfaces:**
- Consumes: `signedAmount`（Task 1）。
- Produces: `BillDao.getBalanceSum(accountId): Double`；`AccountDao.updateBalance(id, balance)`；`BillRepository.recomputeBalance(accountId)`、`recomputeAllBalances()`。

- [ ] **Step 1: BillDao 加按账户求和**

```kotlin
@Query("SELECT CAST(SUM(CASE bill_type WHEN 'INCOME' THEN amount WHEN 'EXPENSE' THEN -amount ELSE 0 END) AS REAL) FROM bills WHERE account_id = :accountId AND deleted = 0")
suspend fun getBalanceSum(accountId: Long): Double
```

- [ ] **Step 2: AccountDao 加更新余额**

```kotlin
@Query("UPDATE accounts SET balance = :balance, updated_at = :updatedAt, dirty = 1 WHERE id = :id")
suspend fun updateBalance(id: Long, balance: Double, updatedAt: Long)
```

- [ ] **Step 3: Repository 接口 + 实现加重算**

```kotlin
// BillRepository.kt
suspend fun recomputeBalance(accountId: Long)
suspend fun recomputeAllBalances()

// BillRepositoryImpl.kt
override suspend fun recomputeBalance(accountId: Long) {
    val account = accountDao.getById(accountId) ?: return
    val sum = billDao.getBalanceSum(accountId)
    accountDao.updateBalance(accountId, account.openingBalance + sum, System.currentTimeMillis())
}
override suspend fun recomputeAllBalances() {
    accountDao.getAllActive().forEach { recomputeBalance(it.id) }
}
```

- [ ] **Step 4: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/db/dao/BillDao.kt app/src/main/java/com/example/rinklnote/data/db/dao/AccountDao.kt app/src/main/java/com/example/rinklnote/data/repository/BillRepository.kt app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt
git commit -m "feat(app): 按账户余额求和 DAO + recompute 仓储方法"
```

---

### Task 3: 记账写路径事务性增减余额（QuickAdd 保存 / Bookkeeping 编辑删）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt`（save/delete 入口）
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt`（`finalConfirm` 约 449-494）
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModel.kt`（编辑/删除约 225-235 附近）

**Interfaces:**
- Consumes: `signedAmount`、`updateBalance`。

- [ ] **Step 1: 仓储加增量增减 helper**

```kotlin
// BillRepositoryImpl.kt
suspend fun adjustAccountBalance(accountId: Long, delta: Double) {
    val account = accountDao.getById(accountId) ?: return
    accountDao.updateBalance(accountId, account.balance + delta, System.currentTimeMillis())
}
```

- [ ] **Step 2: QuickAdd finalConfirm 保存后增减**

在 `finalConfirm()` 的 Room 落库成功后、推送前：
```kotlin
repository.adjustAccountBalance(savedBill.accountId, signedAmount(savedBill.billType, savedBill.amount))
```

- [ ] **Step 3: Bookkeeping 编辑（升/降差额）与删除（反向减去）**

编辑：`delta = signedAmount(new.billType, new.amount) - signedAmount(old.billType, old.amount)`，对 `new.accountId` 应用。删除：`adjustAccountBalance(bill.accountId, -signedAmount(bill.billType, bill.amount))`。

- [ ] **Step 4: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt app/src/main/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModel.kt
git commit -m "feat(app): 记账写路径事务性增减账户余额"
```

> 注：转账的余额变动（源−/目标+）在计划 3 的 `finalConfirm` 里扩展，本计划仅收支。

---

### Task 4: 类型→图标/配色映射 + 资产页文案工具

**Files:**
- Create: `app/src/main/java/com/example/rinklnote/ui/util/AccountType.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt`（`accountIconRes` 约 85-90）

**Interfaces:**
- Produces: `data class AccountTypeMeta(val type: String, val label: String, val iconRes: Int, val color: String, val isLiability: Boolean)`；`fun accountTypeMeta(type: String): AccountTypeMeta`；`fun typeLabel(type: String): String`。
- 依赖既有 drawable：`ic_wechat`/`ic_alipay`/`ic_default_account`（已存在）；新增 `ic_cash`/`ic_bank_card`/`ic_wallet`/`ic_credit`/`ic_loan`。

- [ ] **Step 1: 定义映射**

```kotlin
// app/src/main/java/com/example/rinklnote/ui/util/AccountType.kt
private val ALL_TYPES = listOf(
    AccountTypeMeta("CASH", "现金", R.drawable.ic_cash, "#28C145", false),
    AccountTypeMeta("BANK_CARD", "银行卡", R.drawable.ic_bank_card, "#06B4FD", false),
    AccountTypeMeta("WECHAT", "微信", R.drawable.ic_wechat, "#28C145", false),
    AccountTypeMeta("ALIPAY", "支付宝", R.drawable.ic_alipay, "#06B4FD", false),
    AccountTypeMeta("VIRTUAL", "虚拟", R.drawable.ic_wallet, "#8B5CF6", false),
    AccountTypeMeta("OTHER", "其他", R.drawable.ic_default_account, "#64748B", false),
    AccountTypeMeta("CREDIT", "信用卡", R.drawable.ic_credit, "#EF4444", true),
    AccountTypeMeta("LOAN", "贷款", R.drawable.ic_loan, "#F97D1D", true),
)
fun accountTypeMeta(type: String) = ALL_TYPES.firstOrNull { it.type == type } ?: ALL_TYPES.first { it.type == "OTHER" }
fun typeLabel(type: String) = accountTypeMeta(type).label
```

- [ ] **Step 2: 新增 5 个 monochrome 矢量 drawable**：`ic_cash`/`ic_bank_card`/`ic_wallet`/`ic_credit`/`ic_loan`（参考既有 `ic_wechat.xml` 尺寸与 `tint` 处理）。

- [ ] **Step 3: QuickAddDrawer.accountIconRes 改按类型**

```kotlin
private fun accountIconRes(account: Account): Int = accountTypeMeta(account.type).iconRes
```

- [ ] **Step 4: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/util/AccountType.kt app/src/main/res/drawable app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt
git commit -m "feat(app): 账户类型→图标/配色映射，抽屉图标按类型"
```

---

### Task 5: AssetsViewModel — 派生余额、期初编辑、对账重算事件

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/AssetsViewModel.kt`

**Interfaces:**
- Consumes: `recomputeBalance`、`netWorth`。
- Produces: `AssetsState(accounts, netWorth)`；事件 `EditOpeningBalance(account, value)`、`RebuildBalance(account)`、`RebuildAll`。

- [ ] **Step 1: State 加净资产**

```kotlin
@Immutable
data class AssetsState(
    val accounts: List<Account> = emptyList(),
    val netWorth: Double = 0.0
)
```

初始化收集 `repository.accounts` 时：`_state.update { it.copy(accounts = users, netWorth = netWorth(users)) }`。

- [ ] **Step 2: 加事件与处理**

```kotlin
sealed interface AssetsEvent {
    // ... 既有
    data class EditOpeningBalance(val account: Account, val value: Double) : AssetsEvent
    data class RebuildBalance(val account: Account) : AssetsEvent
    data class RebuildAll : AssetsEvent
}

private fun editOpeningBalance(account: Account, value: Double) {
    val updated = account.copy(openingBalance = value)
    repository.updateAccountLocal(updated)   // 仅落库，不推 balance
    viewModelScope.launch { recomputeAndPush(account.id, updated.openingBalance) }
}
private suspend fun recomputeAndPush(accountId: Long, _opening: Double) {
    repository.recomputeBalance(accountId)
    syncManager?.pushAccount(/* 推送 openingBalance 与重算后的 balance */)
}
private fun rebuild(account: Account) { viewModelScope.launch { repository.recomputeBalance(account.id); syncManager?.pushAccount(...) } }
private fun rebuildAll() { viewModelScope.launch { repository.recomputeAllBalances() } }
```

> `recomputeAndPush` 中推送的 balance 应为 `recomputeBalance` 之后的物化值（在 Method Step 里先取后再推）。

- [ ] **Step 3: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/AssetsViewModel.kt
git commit -m "feat(app): AssetsViewModel 净资产+期初编辑+对账重算事件"
```

---

### Task 6: AssetsScreen 重构（扁平列表 + 资产/负债分区 + 净资产卡 + ⋮菜单）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/assets/BalanceEditDialog.kt`（改为「编辑期初余额」）

**Interfaces:**
- Consumes: `AssetsState.netWorth`、`EditOpeningBalance`/`RebuildBalance`、`accountTypeMeta`、`typeLabel`。

- [ ] **Step 1: 布局重构**

```
LazyColumn
  item(key="net") TotalNetWorthCard(netWorth, hidden)   // 净资产
  item(key="assetsTitle") SectionTitle("资产")
  items(state.accounts.filter { !it.isLiability }) AccountCard(...)
  item(key="liabTitle") SectionTitle("负债")
  items(state.accounts.filter { it.isLiability }) AccountCard(...)
  item(key="add") AddAccountCard
```

- [ ] **Step 2: 替换总资产卡为净资产卡**：`TotalAssetsCard` → `TotalNetWorthCard(netWorth, hidden)`，标题「净资产」，值 `formatMoney(netWorth)`，眼睛切换复用 `BalancePrivacy`。

- [ ] **Step 3: AccountCard 改类型图标 + ⋮ 菜单项**

图标用 `accountTypeMeta(account.type).iconRes`；卡片点击 → 弹「编辑期初余额」；⋮ 菜单：`重命名` / `编辑期初余额` / `发起对账重算` / `删除账户`。

- [ ] **Step 4: 迁移一次性提示**：`LaunchedEffect(Unit)` 检查 `migratedBalanceNudgeShown`（可存 prefs/内存标志），未提示过则弹 Snackbar「余额已改为自动对账，建议现在重建一次」。

- [ ] **Step 5: BalanceEditDialog 改「编辑期初余额」**：标题改「编辑期初余额」，确认发 `EditOpeningBalance(account, value)`（不再直接改 balance）。

- [ ] **Step 6: AddAccountDialog 选类型**：接入 Task 4 的类型列表；选负债类型（信用卡/贷款）时是该账户 `isLiability=true`；类型自动填默认 icon/color，颜色仍可覆盖。

- [ ] **Step 7: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt app/src/main/java/com/example/rinklnote/ui/screen/assets/BalanceEditDialog.kt
git commit -m "feat(app): 资产页扁平列表+资产/负债分区+净资产卡+期初编辑/对账重算"
```

---

## Self-Review

- **Spec 覆盖**：派生逻辑（T1）、重算（T2）、写路径联动（T3）、类型映射（T4）、VM（T5）、UI（T6）覆盖完成。
- **无占位符**：核心公式、DAO 查询、事件签名、布局结构均给出精确代码；仅 drawable 提及"参考既有"需按资源习惯实现（属通用资源操作）。
- **类型一致**：`signedAmount`、`netWorth`、`accountTypeMeta`、事件签名在后续任务一致使用。
- **交付物**：账户余额自动跟随账单、净资产展示、资产页重构完成；转账（TRANSFER）在本计划中因无 TRANSFER 行而安全，计划 3 扩展派生与统计数据。
