# App + Web 使用逻辑一致性修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 7 项不符合使用逻辑的问题：记账页月份切换统一口径、备注手动输入、确认流程一步化、App 搜索/筛选/导出、Web 账单编辑/删除、Web 资产编辑余额。

**Architecture:** 遵循现有分层。App 走 MVVM（State+Event 模式 + Channel 一次性 effect）；Server 沿用 Exposed service + Ktor route（JWT 保护）；Web 沿用单文件 SPA 的 `api()` + `render()` 模式。账户余额为全局单套，新增 `PUT /api/accounts/{id}`。

**Tech Stack:** Kotlin 2.0.21 / Compose M3 / Room（App）、Ktor + Exposed + H2（Server）、原生 JS SPA（Web）。

## Global Constraints

- 数字键盘保持自定义 `NumericKeypad` 方案；系统 IME 仅用于备注输入框与搜索框（有意例外）。
- UI 文案一律中文。
- 不新增依赖；不引入新库。
- 遵循现有模式：ViewModel 定义 `@Immutable State` + sealed `Event`；一次性 effect 走 `Channel<QuickAddEffect>`；`View` 层不写业务逻辑。
- 测试命令（Windows，`GRADLE_USER_HOME` 必须在无撇号路径）：
  - App：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:test --tests "<类名>"`
  - Server：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "<类名>"`
- 提交时按路径显式暂存源码（`git add <具体文件>`），**不要** `git add -A`（避免 server/build 产物污染）。

---

## 任务总览

| Task | 内容 | 平台 |
|------|------|------|
| 1 | `PUT /api/accounts/{id}` 余额端点 | Server |
| 2 | BookkeepingViewModel 月份窗口 + 单测 | App |
| 3 | 记账页月份视图（顶部导航/图表月份窗口/MonthDetail 收敛） | App |
| 4 | 确认流程一步化 | App |
| 5 | RemarkInputSheet 备注手动输入 | App |
| 6 | 筛选栏 + 搜索 + 导出 CSV | App |
| 7 | Web 账单编辑/删除 | Web |
| 8 | Web 资产编辑余额 | Web |

---

## Task 1: Server — `PUT /api/accounts/{id}` 余额端点

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/BillServiceTest.kt`

**Interfaces:**
- Produces: `BillService.updateAccountBalance(id: Long, balance: Double): AccountDTO?` — 账户存在返回更新后 `AccountDTO`，否则 null。
- Produces: `PUT /api/accounts/{id}`（JWT），body `{ "balance": Double }`，成功返回 `AccountDTO`，非法余额 400，不存在 404，未授权 401。

- [ ] **Step 1: 写失败测试**（追加到 `BillServiceTest.kt`，在类内新增两个 `@Test`）

```kotlin
@Test
fun `updateAccountBalance updates and returns the account`() {
    val before = service.getAccounts().firstOrNull { it.name == "微信" } ?: error("微信 missing")
    val updated = service.updateAccountBalance(before.id, 500.0)
    assertNotNull(updated)
    assertEquals(before.id, updated!!.id)
    assertEquals(500.0, updated.balance, 0.0001)
    val after = service.getAccounts().first { it.id == before.id }
    assertEquals(500.0, after.balance, 0.0001)
}

@Test
fun `updateAccountBalance returns null for unknown id`() {
    assertNull(service.updateAccountBalance(99999, 1.0))
}
```

若 `assertNull` / `assertNotNull` 未 import，加：
```kotlin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.BillServiceTest"`
Expected: FAIL — 编译错误 `unresolved reference: updateAccountBalance`

- [ ] **Step 3: 实现 `updateAccountBalance`**（加在 `BillService.getAccounts()` 之后）

```kotlin
fun updateAccountBalance(id: Long, balance: Double): AccountDTO? = transaction {
    val row = AccountsTable.selectAll()
        .where { AccountsTable.id eq id }
        .singleOrNull()
        ?: return@transaction null
    AccountsTable.update({ AccountsTable.id eq id }) {
        it[AccountsTable.balance] = balance
    }
    AccountDTO(
        id = id,
        name = row[AccountsTable.name],
        balance = balance,
        iconColor = row[AccountsTable.iconColor]
    )
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.BillServiceTest"`
Expected: PASS（原有用例 + 新增 2 个）

- [ ] **Step 5: 加路由**（在 `BillRoutes.kt` 顶部加 DTO，在 `authenticate("auth-jwt")` 块内加 route）

```kotlin
@Serializable
data class UpdateBalanceRequest(val balance: Double)
```

在 `billRoutes` 的 `authenticate("auth-jwt") { ... }` 块内（`route("/api/bills")` 之后）追加：

```kotlin
route("/api/accounts") {
    put("/{id}") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal?.payload?.getClaim("userId")?.asLong()
            ?: return@put call.respond(HttpStatusCode.Unauthorized)
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
        val body = call.receive<UpdateBalanceRequest>()
        require(body.balance >= 0 && body.balance.isFinite()) { "余额不能为负" }
        val updated = billService.updateAccountBalance(id, body.balance)
        if (updated != null) {
            call.respond(updated)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to "账户不存在"))
        }
    }
}
```

- [ ] **Step 6: 编译通过 + 提交**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/BillService.kt \
        server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/BillServiceTest.kt
git commit -m "feat(server): PUT /api/accounts/{id} 账户余额更新接口"
```

---

## Task 2: App — BookkeepingViewModel 月份窗口 + 单测

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModel.kt`
- Test: `app/src/test/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModelTest.kt`（新建）

**Interfaces:**
- Consumes: `BillRepository.observeBillsByMonth(monthStart, nextMonthStart)`、`getTotalExpense/Income(monthStart, nextMonthStart)`
- Produces: `BookkeepingState.selectedMonthOffset: Int`（0=本月，-1=上月）；`BookkeepingEvent.SelectMonth(offset)`；`BookkeepingViewModel.selectMonth(offset: Int)`
- 移除 `BookkeepingState.monthBills`、`BookkeepingState.monthOffset`；移除 `collectMonthBills`。

- [ ] **Step 1: 写失败测试**（新建 `app/src/test/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModelTest.kt`）

```kotlin
package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * BookkeepingViewModel month-window logic: the main bill list, chart source and
 * totals all follow selectedMonthOffset. Proves the window (not fixed "today-10d")
 * is what drives observeBillsByMonth / getTotalExpense / getTotalIncome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookkeepingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeBillRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeBillRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVM(): BookkeepingViewModel {
        val vm = BookkeepingViewModel(repo, syncManager = null)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `initial window is the current month`() = runTest(dispatcher) {
        val vm = newVM()
        assertEquals(listOf(getMonthStart(0) to getNextMonthStart(0)), repo.billRanges)
    }

    @Test
    fun `selecting previous month switches window and totals`() = runTest(dispatcher) {
        val vm = newVM()
        vm.selectMonth(-1)
        advanceUntilIdle()
        assertEquals(getMonthStart(-1) to getNextMonthStart(-1), repo.billRanges.last())
        assertEquals(getMonthStart(-1) to getNextMonthStart(-1), repo.totalRanges.last())
        assertEquals(-1, vm.state.value.selectedMonthOffset)
    }

    @Test
    fun `same month select is a no-op`() = runTest(dispatcher) {
        val vm = newVM()
        val before = repo.billRanges.size
        vm.selectMonth(0)
        advanceUntilIdle()
        assertEquals(before, repo.billRanges.size)
    }

    /** Repository fake that records the month windows it is asked to observe/aggregate. */
    private class FakeBillRepository : BillRepository {
        override val expenseCategories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
        override val incomeCategories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
        override val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(emptyList())

        val billRanges = mutableListOf<Pair<Long, Long>>()
        val totalRanges = mutableListOf<Pair<Long, Long>>()

        override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> {
            billRanges += monthStart to nextMonthStart
            return flowOf(emptyList())
        }

        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double {
            totalRanges += monthStart to nextMonthStart
            return 0.0
        }

        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double {
            totalRanges += monthStart to nextMonthStart
            return 0.0
        }

        override fun observeAllBills(): Flow<List<Bill>> = flowOf(emptyList())
        override fun observeTemplates(): Flow<List<BillTemplate>> = flowOf(emptyList())
        override fun observeBudgets(): Flow<List<Budget>> = flowOf(emptyList())
        override suspend fun addBill(bill: Bill): Long = 1
        override suspend fun updateBill(bill: Bill) {}
        override suspend fun deleteBill(bill: Bill) {}
        override suspend fun updateAccount(account: Account) {}
        override suspend fun getSubCategories(parentId: Long): List<SubCategory> = emptyList()
        override suspend fun getBudget(monthStart: Long): Budget? = null
        override suspend fun upsertBudget(budget: Budget) {}
        override suspend fun getUnsyncedBudgets(): List<Budget> = emptyList()
        override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun deleteBudgetByServerId(serverId: Long) {}
        override suspend fun clearLocalData() {}
        override suspend fun loadReferenceData() {}
        override suspend fun seedIfNeeded() {}
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:test --tests "com.example.rinklnote.ui.viewmodel.BookkeepingViewModelTest"`
Expected: FAIL — 编译错误（`selectedMonthOffset` 不存在等）

- [ ] **Step 3: 改 `BookkeepingState`**（`BookkeepingViewModel.kt`）

替换 `BookkeepingState` 中 `monthBills` / `monthOffset` 字段：

```kotlin
    // Offset of the month currently shown: 0 = current month, -1 = previous.
    // Drives the main list, the chart and the totals together.
    val selectedMonthOffset: Int = 0,
```

删除字段：
```kotlin
    val monthBills: List<Bill> = emptyList(),
    ...
    val monthOffset: Int = 0,
```

- [ ] **Step 4: 改事件与 VM 逻辑**

在 sealed `BookkeepingEvent` 中加：
```kotlin
    data class SelectMonth(val offset: Int) : BookkeepingEvent
```

`onEvent` 的 `when` 加分支：
```kotlin
            is BookkeepingEvent.SelectMonth -> selectMonth(event.offset)
```

替换 `collectBills()`：
```kotlin
    private fun collectBills() {
        billCollectorJob?.cancel()
        val offset = _state.value.selectedMonthOffset
        billCollectorJob = viewModelScope.launch {
            val monthStart = getMonthStart(offset)
            val nextMonthStart = getNextMonthStart(offset)
            repository.observeBillsByMonth(monthStart, nextMonthStart).collect { bills ->
                _state.update { it.copy(bills = bills, isLoading = false) }
            }
        }
    }
```

删除 `collectMonthBills`、`monthBillCollectorJob` 字段、`init` 中的 `collectMonthBills()` 调用。

替换 `refreshTotals()`：
```kotlin
    private fun refreshTotals() {
        viewModelScope.launch {
            val offset = _state.value.selectedMonthOffset
            _state.update { it.copy(isLoading = true) }
            val monthStart = getMonthStart(offset)
            val nextMonthStart = getNextMonthStart(offset)
            val expense = repository.getTotalExpense(monthStart, nextMonthStart)
            val income = repository.getTotalIncome(monthStart, nextMonthStart)
            _state.update { it.copy(totalExpense = expense, totalIncome = income, isLoading = false) }
        }
    }
```

替换 `selectMonth`：
```kotlin
    /** Switches every month-scoped view (list/chart/totals) to a different month. */
    fun selectMonth(offset: Int) {
        if (_state.value.selectedMonthOffset == offset) return
        _state.update { it.copy(selectedMonthOffset = offset) }
        collectBills()
        refreshTotals()
    }
```

- [ ] **Step 5: 最小修复编译**（`BookkeepingScreen.kt`，让工程能编译）

当前 `BookkeepingScreen.kt:163-174` 的 `MonthDetailOverlay` 调用引用已删字段。把其中 `monthOffset`/`onPrevMonth`/`onNextMonth` 改为由 `selectedMonthOffset` 驱动（保持 `MonthDetailOverlay` 现有签名不变，后续 Task 3 收敛）：

```kotlin
        MonthDetailOverlay(
            visible = showMonthDetail,
            monthBills = state.bills,
            monthOffset = state.selectedMonthOffset,
            onPrevMonth = { viewModel.selectMonth(state.selectedMonthOffset - 1) },
            onNextMonth = { viewModel.selectMonth(state.selectedMonthOffset + 1) },
            onDismiss = {
                showMonthDetail = false
                if (state.selectedMonthOffset != 0) viewModel.selectMonth(0)
            }
        )
```

- [ ] **Step 6: 运行测试确认通过 + 编译**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:test --tests "com.example.rinklnote.ui.viewmodel.BookkeepingViewModelTest" :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt \
        app/src/test/java/com/example/rinklnote/ui/viewmodel/BookkeepingViewModelTest.kt
git commit -m "feat: 记账页列表/图表/总额统一按月窗口，顶部可切月(VM 层)"
```

---

## Task 3: App — 记账页月份视图（顶部导航 + 图表月份窗口 + MonthDetail 收敛）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/MonthDetailOverlay.kt`

**Interfaces:**
- Consumes: `state.selectedMonthOffset`、`viewModel.selectMonth(offset)`
- Produces: `MonthDetailOverlay(visible, monthLabel, bills, onDismiss)`

- [ ] **Step 1: 屏幕状态 + 月份标签**

在 `BookkeepingScreen` 的 interaction state 区（`var showMonthDetail` 之后）加：
```kotlin
    var showMonthNav by remember { mutableStateOf(false) }
```
在 `val state by viewModel.state.collectAsStateWithLifecycle()` 之后加：
```kotlin
    val monthLabel = remember(state.selectedMonthOffset) {
        val d = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong())
        "${d.year}年${d.monthValue}月"
    }
```

- [ ] **Step 2: TopBar 改为月份标签 + 可点**

`TopBar` 签名改为：
```kotlin
@Composable
private fun TopBar(
    monthLabel: String,
    onMonthClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit
)
```
`date` 参数删除；中央 `Text` 改为：
```kotlin
        Text(
            text = monthLabel,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(onClick = onMonthClick)
        )
```
调用处（`item(key = "topbar")`）改为：
```kotlin
                TopBar(
                    monthLabel = monthLabel,
                    onMonthClick = { showMonthNav = !showMonthNav },
                    onOpenDrawer = onOpenDrawer,
                    onFinanceClick = onFinanceClick,
                    onMoreClick = onMoreClick
                )
```
删除 `import com.example.rinklnote.util.toHeaderString`（不再使用）。

- [ ] **Step 3: 月导航条 MonthNavigator**

在 `item(key = "topbar")` 之后、`item(key = "chart")` 之前插入：
```kotlin
            if (showMonthNav) {
                item(key = "monthnav") {
                    MonthNavigator(
                        offset = state.selectedMonthOffset,
                        onPrev = { viewModel.selectMonth(state.selectedMonthOffset - 1) },
                        onNext = { viewModel.selectMonth(state.selectedMonthOffset + 1) },
                        onBackToNow = { viewModel.selectMonth(0) }
                    )
                }
            }
```
新增 `MonthNavigator` composable（文件内，`TopBar` 之后）：
```kotlin
@Composable
private fun MonthNavigator(
    offset: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit
) {
    val d = LocalDate.now().plusMonths(offset.toLong())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPrev) {
            Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = "${d.year}年${d.monthValue}月",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = onNext, enabled = offset < 0) {
            Text(
                "›",
                fontSize = 26.sp,
                color = if (offset < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (offset != 0) {
            TextButton(onClick = onBackToNow) {
                Text("回本月", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
```
需要新增 import：`androidx.compose.foundation.layout.Arrangement`（`TextButton`、`shadow`、`clip`、`RoundedCornerShape` 已引入）。

- [ ] **Step 4: 图表月份窗口**

删除旧的 `computeChartData` 函数，替换为按月计算（`java.time.temporal.ChronoUnit` 用全限定名，无需新增 import）：
```kotlin
private fun computeMonthChartData(bills: List<Bill>, offset: Int): Pair<List<Float>, List<String>> {
    val zone = ZoneId.systemDefault()
    val firstDay = LocalDate.now().plusMonths(offset.toLong()).withDayOfMonth(1)
    val lastDay = if (offset == 0) LocalDate.now() else firstDay.plusMonths(1).minusDays(1)
    val days = (java.time.temporal.ChronoUnit.DAYS.between(firstDay, lastDay).toInt()) + 1
    val data = List(days) { firstDay.plusDays(it.toLong()) }
    val values = data.map { d ->
        bills.filter { bill ->
            bill.billType == "EXPENSE" &&
                Instant.ofEpochMilli(bill.date).atZone(zone).toLocalDate() == d
        }.sumOf { it.amount }.toFloat()
    }
    val labels = data.map { "${it.monthValue}.${it.dayOfMonth}" }
    return values to labels
}
```
调用处改为：
```kotlin
    val (chartData, chartLabels) = remember(state.bills, state.selectedMonthOffset) {
        computeMonthChartData(state.bills, state.selectedMonthOffset)
    }
```
（`java.time.temporal.ChronoUnit` 在代码中已用全限定名，无需 import。）

- [ ] **Step 5: MonthDetailOverlay 收敛为当前月汇总**

`MonthDetailOverlay.kt` 签名改为：
```kotlin
@Composable
fun MonthDetailOverlay(
    visible: Boolean,
    monthLabel: String,
    bills: List<Bill>,
    onDismiss: () -> Unit
)
```
删除 `monthOffset` / `onPrevMonth` / `onNextMonth` 参数、头部 ‹ › 按钮、`monthTitle(offset)` 函数。头部改为：
```kotlin
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = monthLabel,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
```
体内所有 `monthBills` 引用改为 `bills`（第 91-102 行的 expenseTotal/incomeTotal/grouped/isEmpty 判断）。删除 `import java.time.LocalDate`（`monthTitle` 已删）。

`BookkeepingScreen.kt` 调用处改为：
```kotlin
        MonthDetailOverlay(
            visible = showMonthDetail,
            monthLabel = monthLabel,
            bills = state.bills,
            onDismiss = { showMonthDetail = false }
        )
```

- [ ] **Step 6: 编译 + 提交**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/MonthDetailOverlay.kt
git commit -m "feat: 记账页顶部月份导航，图表按选中月，月明细收敛为当前月汇总"
```

---

## Task 4: App — 确认流程一步化

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt`
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`
- Test: `app/src/test/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModelTest.kt`

**Interfaces:**
- Removes: `QuickAddState.confirmed`、`QuickAddEffect.ConfirmRequested`
- Preserves: `QuickAddEffect.FinalConfirmCompleted`、`finalConfirm()`、`resetConfirming()`

- [ ] **Step 1: 更新失败测试**

`QuickAddViewModelTest.kt` 中替换 3 个相关测试，更新 2 个 NLP 测试：

删除 `confirm requires selected category and account`，改为：
```kotlin
    @Test
    fun `confirm saves the bill directly`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(20.0, repo.addedBills[0].amount, 0.0001)
        assertEquals("三餐", repo.addedBills[0].categoryName)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
    }
```
`confirm without account stays unconfirmed` 改为：
```kotlin
    @Test
    fun `confirm without account saves nothing`() = runTest(dispatcher) {
        repo.accounts.value = emptyList()
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
    }
```
`confirm with missing amount is ignored` 改为：
```kotlin
    @Test
    fun `confirm with missing amount is ignored`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
    }
```
NLP 两个测试改为断言保存：
```kotlin
    @Test
    fun `nlp local parse fills amount and category and saves`() = runTest(dispatcher) {
        val vm = newVM() // api = null → local VoiceParser fallback
        vm.onEvent(QuickAddEvent.NlpInput("午餐20元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("20", s.amount)
        assertEquals("三餐", s.selectedCategory?.name)
        assertEquals("", s.nlpInput)
        assertFalse(s.isParsing)
        assertEquals(1, repo.addedBills.size)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
    }

    @Test
    fun `nlp local parse without a recognized category does not save`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.NlpInput("普通消费30元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("30", s.amount)
        assertEquals("三餐", s.selectedCategory?.name)
        assertEquals(0, repo.addedBills.size)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:test --tests "com.example.rinklnote.ui.viewmodel.QuickAddViewModelTest"`
Expected: FAIL — 断言不符（`confirm` 不再写 `confirmed`，effect 不再是 `ConfirmRequested`）

- [ ] **Step 3: 改 QuickAddViewModel**

`QuickAddState` 删除字段：
```kotlin
    val confirmed: Boolean = false,
```
从 `QuickAddEffect` 中删除 `ConfirmRequested`（只保留 `FinalConfirmCompleted`）：
```kotlin
sealed interface QuickAddEffect {
    data object FinalConfirmCompleted : QuickAddEffect
}
```
`confirm()` 改为直接一步保存：
```kotlin
    private fun confirm() {
        // One-step: keypad confirm saves immediately (anti-misclick two-phase removed).
        val s = _state.value
        if (s.amount.toDoubleOrNull() == null) return
        if (s.selectedCategory == null) return
        if (s.selectedAccount == null) return
        finalConfirm()
    }
```
`onNlpSubmit` 中，将 `_effects.trySend(QuickAddEffect.ConfirmRequested)` 替换为 `finalConfirm()`：
```kotlin
                        // One-step: NLP intent saves directly
                        finalConfirm()
                        return@launch
```
`applyLocalParse` 中，将 `_effects.trySend(QuickAddEffect.ConfirmRequested)` 替换为：
```kotlin
        // Category auto-selected → save directly (one-step)
        if (cat != null) {
            finalConfirm()
        }
```

- [ ] **Step 4: 改 AppNavigation + QuickAddDrawer**

`AppNavigation.kt`：
- 删除 `var showConfirmed by remember { mutableStateOf(false) }`。
- `openDrawer` 删除 `showConfirmed = false`。
- `LaunchedEffect(pagerState.currentPage)` 删除 `showConfirmed = false`。
- effect 收集 `when` 删除 `ConfirmRequested` 分支，`FinalConfirmCompleted` 分支加 Toast：
```kotlin
                is QuickAddEffect.FinalConfirmCompleted -> {
                    Toast.makeText(context, "已记账", Toast.LENGTH_SHORT).show()
                    bookkeepingVM.onEvent(BookkeepingEvent.Refresh)
                    quickAddVM.reset()
                    showDrawer = false
                }
```
- `QuickAddDrawer(...)` 调用删除 `confirmed = showConfirmed` 参数。
- `onAmountTap` 删除 `showConfirmed = false`。

`QuickAddDrawer.kt`：
- 签名删除 `confirmed: Boolean = false` 与 `onFinalConfirm: () -> Unit = {}` 参数。
- `DrawerContent` 签名同步删除，`CountAfter` 分支改为直接 `CountBefore`：
```kotlin
        CountBefore(state, onAmountTap)
```
- 删除 `CountAfter` composable 与其中不再使用的 `Animatable`/`graphicsLayer` import（若其它处未用）。

- [ ] **Step 5: 运行测试 + 编译**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:test --tests "com.example.rinklnote.ui.viewmodel.QuickAddViewModelTest" :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt \
        app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt \
        app/src/test/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModelTest.kt
git commit -m "feat: 记账确认统一为一步，去除绿勾门禁并加已记账Toast"
```

---

## Task 5: App — RemarkInputSheet 备注手动输入

**Files:**
- Create: `app/src/main/java/com/example/rinklnote/ui/component/RemarkInputSheet.kt`
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BillEditOverlay.kt`

**Interfaces:**
- Produces: `RemarkInputSheet(initialText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: 新建 RemarkInputSheet**

`app/src/main/java/com/example/rinklnote/ui/component/RemarkInputSheet.kt`：
```kotlin
package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom sheet for manually typing a remark — the one deliberate use of the
 * system IME (numeric entry keeps the custom NumericKeypad).
 */
@Composable
fun RemarkInputSheet(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Text("输入备注", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("备注", fontSize = 14.sp) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(0.dp))
                Button(onClick = { onConfirm(text) }) {
                    Text("确定")
                }
            }
        }
    }
}
```

- [ ] **Step 2: AppNavigation 接入**

`AppNavigation.kt`：
- 加状态：`var showRemarkSheet by remember { mutableStateOf(false) }`。
- 键盘 overlay 的 `onRemarkClick` 改为 `{ showRemarkSheet = true }`（原来 `{ onVoiceInput() }`）。
- 在文件 `Box` 内（QuickAdd 键盘 overlay 之后）渲染：
```kotlin
        if (showRemarkSheet) {
            RemarkInputSheet(
                initialText = quickAddState.remark,
                onConfirm = { text ->
                    showRemarkSheet = false
                    quickAddVM.onEvent(QuickAddEvent.RemarkChanged(text))
                },
                onDismiss = { showRemarkSheet = false }
            )
        }
```
- import：`com.example.rinklnote.ui.component.RemarkInputSheet`。

- [ ] **Step 3: BillEditOverlay 接入**

`BillEditOverlay.kt`：
- 加状态：`var showRemark by remember { mutableStateOf(false) }`。
- `NumericKeypad` 的 `onRemarkClick = {}` 改为 `{ showRemark = true }`。
- 最外层 `Column` 包一层 `Box(Modifier.fillMaxSize())`：`Column` 原样放进 Box，再在 Box 内 `Column` 之后渲染 RemarkInputSheet 作为覆盖层（RemarkInputSheet 自带全屏 scrim，不能内联进 Column，否则布局错位）：
```kotlin
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
        ) {
            // ... 原内容不变
        }

        if (showRemark) {
            RemarkInputSheet(
                initialText = remark,
                onConfirm = { text ->
                    showRemark = false
                    remark = text
                },
                onDismiss = { showRemark = false }
            )
        }
    }
```
- 需要新增 import：`androidx.compose.foundation.layout.Box`、`com.example.rinklnote.ui.component.RemarkInputSheet`（`Box` 可能已在文件内，若已 import 则不用重复）。

- [ ] **Step 4: 编译 + 提交**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/java/com/example/rinklnote/ui/component/RemarkInputSheet.kt \
        app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BillEditOverlay.kt
git commit -m "feat: 备注可手动输入（RemarkInputSheet），语音入口保留在抽屉"
```

---

## Task 6: App — 筛选栏 + 搜索 + 导出 CSV

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt`
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: FileProvider 配置**

`app/src/main/res/xml/file_paths.xml`（新建）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="exports" path="exports/" />
</paths>
```

`AndroidManifest.xml` 的 `<application>` 内加（`</activity>` 之后）：
```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 2: 筛选状态 + 过滤 + 筛选栏 UI**

`BookkeepingScreen.kt` 需新增 import：
```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
```

interaction state 区加：
```kotlin
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
```
替换分组计算：
```kotlin
    // Client-side filter over the selected month's bills (search + category)
    val filteredBills = remember(state.bills, searchQuery, categoryFilter) {
        state.bills.filter { b ->
            (searchQuery.isBlank() || (b.remark ?: "").contains(searchQuery) || b.categoryName.contains(searchQuery)) &&
                (categoryFilter == null || b.categoryName == categoryFilter)
        }
    }
    val groupedBills = remember(filteredBills) { groupBillsByDate(filteredBills) }
    val filterCategories = remember(state.bills) {
        state.bills.map { it.categoryName }.distinct().sorted()
    }
```

在 `item(key = "chart")` 之后插入筛选栏 item：
```kotlin
            item(key = "filterbar") {
                FilterBar(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    categoryFilter = categoryFilter,
                    categories = filterCategories,
                    onCategoryChange = { categoryFilter = it },
                    onExport = { exportBills(context, filteredBills) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
```
`val context = LocalContext.current` 加到 `BookkeepingScreen` 顶部。

新增 `FilterBar` composable：
```kotlin
@Composable
private fun FilterBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    categoryFilter: String?,
    categories: List<String>,
    onCategoryChange: (String?) -> Unit,
    onExport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                placeholder = { Text("搜索备注/分类", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onExport) { Text("导出", fontSize = 14.sp) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(text = "全部", selected = categoryFilter == null, onClick = { onCategoryChange(null) })
            categories.forEach { c ->
                FilterChip(text = c, selected = categoryFilter == c, onClick = { onCategoryChange(c) })
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}
```

- [ ] **Step 3: 导出函数**

`BookkeepingScreen.kt` 底部加导出函数（需要 import `android.net.Uri`、`android.content.Intent`、`androidx.core.content.FileProvider`、`java.io.File`、`androidx.compose.ui.platform.LocalContext`）：
```kotlin
private fun exportBills(context: android.content.Context, bills: List<Bill>) {
    val sb = StringBuilder("\uFEFF")
    sb.appendLine("日期,类型,分类,子分类,金额,备注,来源")
    val zone = ZoneId.systemDefault()
    bills.forEach { b ->
        val date = java.time.Instant.ofEpochMilli(b.date).atZone(zone).toLocalDate().toString()
        sb.appendLine(
            listOf(date, b.billType, b.categoryName, b.subCategoryName ?: "", b.amount, b.remark ?: "", b.source)
                .joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }
        )
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "rinklnote.csv").apply { writeText(sb.toString(), Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "导出账单"))
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "未找到可分享的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 4: 编译 + 提交**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt \
        app/src/main/res/xml/file_paths.xml \
        app/src/main/AndroidManifest.xml
git commit -m "feat: 记账页搜索/分类筛选/导出CSV（分享面板）"
```

---

## Task 7: Web — 账单编辑/删除

**Files:**
- Modify: `web/index.html`

**Interfaces:**
- Consumes: `PUT /api/bills/{id}`、`DELETE /api/bills/{id}`（已存在）、`S.cats`/`S.accts`

- [ ] **Step 1: 表格加操作列 + 移除内嵌分类 select**

`renderBills` 中表头改为：
```js
  ['日期','分类','子分类','金额','备注','来源','操作'].forEach(l => thead.append(h('th',null,l)));
```
`f.forEach(b => { ... })` 循环内，把分类单元格改为纯文本（移除内嵌 select）：
```js
    const catCell = h('td', null, b.categoryName);
    if (b.subCategoryName) catCell.textContent = b.categoryName + ' / ' + b.subCategoryName;
    tr.appendChild(catCell);
    tr.append(h('td',{style:'color:var(--muted)'}, b.subCategoryName || '-'));
    tr.append(h('td',{className:'amount',style:'text-align:right;font-weight:700;color:'+(b.billType==='EXPENSE'?'var(--expense)':'var(--income)')}, (b.billType==='EXPENSE'?'-':'')+'¥'+b.amount.toFixed(2)));
    tr.append(h('td',{style:'color:var(--muted)'}, b.remark||'-'));
    const tagCls = b.source==='QQ'?'tag tag-qq':b.source==='WEB'?'tag tag-web':'tag tag-app';
    tr.append(h('td',null, h('span',{className:tagCls},b.source)));
    // Actions: edit / delete
    const ops = h('td', null,
      h('button',{className:'btn btn-outline',style:'padding:3px 8px;font-size:11px;margin-right:6px',onclick:()=>openEditBill(b)},'编辑'),
      h('button',{className:'btn btn-outline danger',style:'padding:3px 8px;font-size:11px',onclick:async()=>{
        if(!confirm('确定删除该账单？')) return;
        const r = await api('/api/bills/'+b.id,{method:'DELETE'});
        S.msg = r.message || '已删除'; await loadData();
      }},'删除')
    );
    tr.appendChild(ops);
    tbody.appendChild(tr);
```

- [ ] **Step 2: 新增 openEditBill 弹窗函数**

在 `renderBills` 函数之前（或之后）加：
```js
function openEditBill(b) {
  const overlay = document.createElement('div');
  overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:200;display:flex;align-items:center;justify-content:center';
  const modal = document.createElement('div');
  modal.style.cssText = 'background:var(--card);border-radius:15px;padding:24px;width:90%;max-width:420px;max-height:90vh;overflow:auto';
  const st = { amount:String(b.amount), billType:b.billType, catId:String(b.categoryId), subCatName:b.subCategoryName||'', acctId:String(b.accountId), remark:b.remark||'' };

  function renderSubs(selCat) {
    const subRow = modal.querySelector('#edit_sub');
    if (!subRow || !selCat || !selCat.subCategories || !selCat.subCategories.length) return;
    subRow.innerHTML = '';
    subRow.appendChild(h('p',{style:'font-size:12px;color:var(--muted);margin:0 0 6px'},'子分类'));
    const sg = h('div',{className:'grid-4'});
    sg.appendChild(h('button',{className:'cat-btn'+(!st.subCatName?' sel':''),onclick:()=>{st.subCatName='';renderSubs(selCat);}},'全部'));
    selCat.subCategories.forEach(sc=>{
      sg.appendChild(h('button',{className:'cat-btn'+(st.subCatName===sc.name?' sel':''),onclick:()=>{st.subCatName=sc.name;renderSubs(selCat);}},sc.name));
    });
    subRow.appendChild(sg);
  }

  function render() {
    modal.innerHTML = '';
    modal.appendChild(h('h3',{style:'margin-bottom:16px'},'编辑账单'));
    const tog = h('div',{className:'toggle',style:'margin-bottom:12px'});
    ['EXPENSE','INCOME'].forEach(t=>{
      tog.appendChild(h('button',{className:st.billType===t?'on '+(t==='EXPENSE'?'exp':'inc'):'',onclick:()=>{st.billType=t;st.catId='';st.subCatName='';render();}},t==='EXPENSE'?'支出':'收入'));
    });
    modal.appendChild(tog);
    modal.appendChild(h('input',{type:'number',step:'0.01',placeholder:'0.00',value:st.amount,oninput:e=>st.amount=e.target.value,style:'width:100%;margin-bottom:12px;font-size:20px;padding:10px 14px;border-radius:10px;border:1.5px solid var(--border)'}));
    modal.appendChild(h('p',{style:'font-size:12px;color:var(--muted);margin:8px 0 6px'},'分类'));
    const catSel = h('select',{onchange:e=>{const p=e.target.value.split('||');st.catId=p[0];st.subCatName='';render();}});
    catSel.append(h('option',{value:''},'选择分类'));
    S.cats.filter(c=>c.billType===st.billType).forEach(c=>{
      catSel.append(h('option',{value:c.id+'||'+c.name,selected:c.id==st.catId},c.name));
    });
    modal.appendChild(catSel);
    const subRow = h('div',{id:'edit_sub',style:'margin-top:8px'});
    modal.appendChild(subRow);
    const selCat = S.cats.find(c=>c.id==st.catId);
    if (selCat && selCat.subCategories && selCat.subCategories.length) renderSubs(selCat);
    modal.appendChild(h('p',{style:'font-size:12px;color:var(--muted);margin:12px 0 6px'},'账户'));
    const acctSel = h('select',{onchange:e=>st.acctId=e.target.value});
    acctSel.append(h('option',{value:''},'选择账户'));
    S.accts.forEach(a=>{acctSel.append(h('option',{value:a.id,selected:a.id==st.acctId},a.name));});
    modal.appendChild(acctSel);
    modal.appendChild(h('input',{type:'text',placeholder:'备注 (可选)',value:st.remark,oninput:e=>st.remark=e.target.value,style:'width:100%;margin-top:12px'}));
    const btnRow = h('div',{style:'display:flex;gap:10px;margin-top:16px'});
    btnRow.appendChild(h('button',{className:'btn btn-outline',style:'flex:1',onclick:()=>overlay.remove()},'取消'));
    btnRow.appendChild(h('button',{className:'btn btn-primary',style:'flex:1',onclick:async()=>{
      if(!st.amount||!st.catId||!st.acctId){S.msg='请填写完整';overlay.remove();render();return;}
      const cat = S.cats.find(c=>c.id==st.catId);
      const r = await api('/api/bills/'+b.id,{method:'PUT',body:JSON.stringify({
        amount:parseFloat(st.amount),billType:st.billType,categoryId:parseInt(st.catId),categoryName:cat.name,
        subCategoryName:st.subCatName||null,accountId:parseInt(st.acctId),remark:st.remark||null
      })});
      if(r.id){S.msg='账单已更新';overlay.remove();await loadData();}
      else{S.msg=r.message||'更新失败';overlay.remove();render();}
    }},'保存'));
    modal.appendChild(btnRow);
  }
  render();
  overlay.appendChild(modal);
  overlay.onclick = (e)=>{ if(e.target===overlay) overlay.remove(); };
  document.body.appendChild(overlay);
}
```

- [ ] **Step 3: 手测**

用浏览器打开本地 web（或部署后公网）：
1. 账单页每行显示「编辑」「删除」。
2. 点编辑 → 弹窗改金额/分类/备注 → 保存 → 列表刷新、`S.msg='账单已更新'`。
3. 点删除 → confirm → 列表移除。
4. 移动端窄屏：操作列出现在横滚表末尾。

- [ ] **Step 4: 提交**

```bash
git add web/index.html
git commit -m "feat(web): 账单行编辑/删除，移除隐蔽分类select"
```

---

## Task 8: Web — 资产编辑余额

**Files:**
- Modify: `web/index.html`

**Interfaces:**
- Consumes: `PUT /api/accounts/{id}`（Task 1）

- [ ] **Step 1: 账户卡加编辑按钮**

`renderAssets` 的 `S.accts.forEach(a => { ... })` 内，卡片 `c` 构建时加操作按钮。把 `c.appendChild(info)` 之后加：
```js
      info.append(h('div',{style:'margin-top:8px'},
        h('button',{className:'btn btn-outline',style:'padding:4px 12px;font-size:11px',onclick:async()=>{
          const val = prompt('修改 '+a.name+' 余额', (a.balance||0));
          if(val===null) return;
          const num = parseFloat(val);
          if(isNaN(num)||num<0){S.msg='请输入有效余额';render();return;}
          const r = await api('/api/accounts/'+a.id,{method:'PUT',body:JSON.stringify({balance:num})});
          S.msg = (r.balance!==undefined) ? '余额已更新' : (r.message||'更新失败');
          render();
        }},'编辑余额')
      ));
```

- [ ] **Step 2: 手测**

1. 资产页每个账户卡出现「编辑余额」。
2. 点它 → prompt 输入 → 保存 → 卡片余额更新。
3. 输入负数/非数字 → `S.msg='请输入有效余额'`。
4. 未登录 → 401 跳登录（现有 `api()` 逻辑）。

- [ ] **Step 3: 提交**

```bash
git add web/index.html
git commit -m "feat(web): 资产页可编辑账户余额"
```

---

## 收尾

- [ ] **App 全量单测**：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:test`
  Expected: 全部 PASS（含既有 SyncManager/VoiceParser 用例）
- [ ] **Server 全量单测**：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test`
  Expected: 全部 PASS（含既有 18 项）
- [ ] **App 装机手测**：切月（本月/上月/空月）、顶部「回本月」、一步记账+Toast、备注输入、搜索/筛选/导出。
- [ ] **部署**（用户惯例，自动进行）：
  - Server：`./gradlew :server:installDist` → tar+scp → systemd 重启 → `curl -X PUT http://127.0.0.1:8080/api/accounts/1 -d '{"balance":0}'` 带 JWT 冒烟。
  - Web：`sudo cp` 到 `/var/www/rinklnote/index.html`，核对 md5。
  - App：`installDebug` 装机。
