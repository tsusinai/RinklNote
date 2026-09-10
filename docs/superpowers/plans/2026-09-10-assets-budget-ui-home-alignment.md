# 资产页与预算页 UI 重构 · 对齐首页风格（实施计划）

> 日期：2026-09-10 ｜ 分支：`assets-budget-ui` ｜ 设计文档：`specs/2026-09-10-assets-budget-ui-home-alignment-design.md`
> 方法论：superpowers · TDD 优先（先写测试→红/绿基线→重构→回归绿）

## 任务总览

| # | 任务 | 验证 |
|---|------|------|
| 1 | 新增 `AssetsViewModelTest.kt`（characterization test） | `:app:test --tests *AssetsViewModelTest` 全绿 |
| 2 | 抽公共 `DefaultHazeBackground` 到 `ui/component/HazeBackground.kt`，首页改调用 | `:app:assembleDebug` 编译通过 |
| 3 | `AppNavigation.kt` 透传 `hazeState` + `backgroundUri` 给 Assets/Plan | 编译通过 |
| 4 | `AssetsScreen.kt` 重构（4a-4e 子任务） | 编译 + `AssetsViewModelTest` 仍绿 |
| 5 | `PlanScreen.kt` 重构（5a-5c 子任务） | 编译 + `BudgetViewModelTest` 仍绿 |
| 6 | 验收：全测 + assembleDebug + 风格清单 | 见任务 6 |

---

## 任务 1：新增 `AssetsViewModelTest.kt`（TDD 基线）

- 文件路径：`app/src/test/java/com/example/rinklnote/ui/viewmodel/AssetsViewModelTest.kt`（新建）
- 要做的：创建 characterization test，仿 `BudgetViewModelTest` 风格，覆盖 `AssetsViewModel` 六事件。VM 行为已存在，测试作回归基线（重构期间必须保持绿）。
- 验证：`:app:test --tests "com.example.rinklnote.ui.viewmodel.AssetsViewModelTest"` 全绿

完整代码（可直接粘贴）：

```kotlin
package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.repository.AccountRepository
import com.example.rinklnote.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 资产页 ViewModel characterization test（Task 1）。
 * 六事件（AddAccount/RenameAccount/ChangeBalance/DeleteAccount/ReconcileAccount/ReconcileAll）
 * 的落库契约：dirty 标记 + 正确字段 + repository 方法调用。
 * syncManager=null 不验证 pushAccount（属集成层，与 BudgetViewModelTest 同边界）。
 * 重构 UI 期间必须保持全绿（回归守护）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeAccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeAccountRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVM(): AssetsViewModel {
        val vm = AssetsViewModel(repo, syncManager = null)
        advanceUntilIdle()
        return vm
    }

    private fun account(id: Long, name: String, balance: Double) =
        Account(id = id, name = name, balance = balance, iconColor = "#28C145", updatedAt = 0L, dirty = false)

    @Test
    fun `state accounts come from observeAccounts flow`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        assertEquals(1, vm.state.value.accounts.size)
        assertEquals("微信", vm.state.value.accounts[0].name)
    }

    @Test
    fun `addAccount inserts with dirty true and correct fields`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(AssetsEvent.AddAccount("支付宝", "#06B4FD", 500.0))
        advanceUntilIdle()
        assertEquals(1, repo.inserted.size)
        val a = repo.inserted.last()
        assertEquals("支付宝", a.name)
        assertEquals(500.0, a.balance, 0.0001)
        assertEquals("#06B4FD", a.iconColor)
        assertTrue(a.dirty)
        assertFalse(a.deleted)
    }

    @Test
    fun `renameAccount updates local with dirty true and new name`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.RenameAccount(orig, "零钱"))
        advanceUntilIdle()
        assertEquals(1, repo.updatedLocal.size)
        val u = repo.updatedLocal.last()
        assertEquals("零钱", u.name)
        assertTrue(u.dirty)
        assertEquals(1L, u.id)
    }

    @Test
    fun `changeBalance updates local with dirty true and new balance`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.ChangeBalance(orig, 250.0))
        advanceUntilIdle()
        assertEquals(1, repo.updatedLocal.size)
        val u = repo.updatedLocal.last()
        assertEquals(250.0, u.balance, 0.0001)
        assertTrue(u.dirty)
    }

    @Test
    fun `deleteAccount soft deletes the account`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.DeleteAccount(orig))
        advanceUntilIdle()
        assertEquals(1, repo.softDeleted.size)
        assertEquals(1L, repo.softDeleted.last().id)
    }

    @Test
    fun `reconcileAccount delegates to repository with opening offset`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.ReconcileAccount(orig, 50.0))
        advanceUntilIdle()
        assertEquals(1, repo.reconciled.size)
        val (acc, offset) = repo.reconciled.last()
        assertEquals(1L, acc.id)
        assertEquals(50.0, offset, 0.0001)
    }

    @Test
    fun `reconcileAll calls reconcileAllAccounts on repository`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(AssetsEvent.ReconcileAll)
        advanceUntilIdle()
        assertTrue(repo.reconcileAllCalled)
    }

    // ---------- fake ----------

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        val inserted = mutableListOf<Account>()
        val updatedLocal = mutableListOf<Account>()
        val softDeleted = mutableListOf<Account>()
        val reconciled = mutableListOf<Pair<Account, Double>>()
        var reconcileAllCalled = false

        override fun observeAccounts(): Flow<List<Account>> = accounts
        override suspend fun insertAccount(account: Account): Long {
            inserted += account
            return inserted.size.toLong()
        }
        override suspend fun updateAccount(account: Account) {}
        override suspend fun updateAccountLocal(account: Account) { updatedLocal += account }
        override suspend fun softDeleteAccount(account: Account) { softDeleted += account }
        override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun getUnsyncedAccounts(): List<Account> = emptyList()
        override suspend fun getAccountByServerId(serverId: Long): Account? = null
        override suspend fun deleteAccountByServerId(serverId: Long) {}
        override suspend fun getAccountNet(accountId: Long): Double = 0.0
        override suspend fun reconcileAccount(account: Account, openingOffset: Double): Account {
            reconciled += account to openingOffset
            return account
        }
        override suspend fun reconcileAllAccounts(): List<Account> {
            reconcileAllCalled = true
            return emptyList()
        }
    }
}
```

---

## 任务 2：抽公共 `DefaultHazeBackground`

- 文件路径：`app/src/main/java/com/example/rinklnote/ui/component/HazeBackground.kt`（新建）
- 要做的：把 `BookkeepingScreen.kt` 里 `private fun DefaultBackgroundLayer` 抽成公共 `DefaultHazeBackground`。

```kotlin
package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 记账/资产/预算页默认背景：柔和的主色→背景渐变，作为毛玻璃的 blur 源。
 * 用户未自选照片时由各页自行铺设；选了照片则由 nav 层 AppBackground 整窗铺满。
 */
@Composable
fun DefaultHazeBackground(hazeState: HazeState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .hazeSource(hazeState)
    )
}
```

- 改 `BookkeepingScreen.kt`：删除 `private fun DefaultBackgroundLayer`，调用处改为 `DefaultHazeBackground(hazeState = hazeState)`，import `com.example.rinklnote.ui.component.DefaultHazeBackground`。
- 验证：`:app:assembleDebug` 编译通过，首页背景外观不变。

---

## 任务 3：`AppNavigation.kt` 透传

- 文件路径：`app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`
- 要做的：给 `AssetsScreen` 与 `PlanScreen` 的 composable 调用补 `backgroundUri` + `hazeState` 参数。

改 `composable("assets")`（第 384 行附近）：
```kotlin
composable("assets") {
    AssetsScreen(
        viewModel = assetsVM,
        backgroundUri = appBackgroundUri,
        hazeState = hazeState
    )
}
```

改 `composable("plan")`（第 369-371 行）：
```kotlin
composable("plan") {
    PlanScreen(
        viewModel = budgetVM,
        isActive = currentRoute == "plan",
        backgroundUri = appBackgroundUri,
        hazeState = hazeState
    )
}
```

- 验证：编译通过（此时 AssetsScreen/PlanScreen 签名尚未改，会编译失败 → **任务 3 与任务 4a/5a 必须一起完成才能编译**。执行顺序：先改签名（4a/5a）再改 nav（3），或三处一起改后统一编译）。

---

## 任务 4：`AssetsScreen.kt` 重构

### 4a：签名扩展 + 根布局改 Box + 默认背景
- 文件路径：`app/src/main/java/com/example/rinklnote/ui/screen/assets/AssetsScreen.kt`
- 改 `fun AssetsScreen(viewModel: AssetsViewModel)` →
  `fun AssetsScreen(viewModel: AssetsViewModel, backgroundUri: String?, hazeState: HazeState)`
- 根 `Column(fillMaxSize.statusBarsPadding.padding(16.dp))` → `Box(fillMaxSize)`；无 `backgroundUri` 时铺 `DefaultHazeBackground(hazeState)`
- `LazyColumn` 首项加 `item { Spacer(height = topBarHeight) }`（`topBarHeight = statusBarsTop + 46.dp`，仿首页）
- 验证：编译通过（配合任务 3）

### 4b：新增悬浮 `AssetsTopBar` + scrim
- 在 `AssetsScreen.kt` 加 private `AssetsTopBar` Composable：`statusBarsPadding` + 左侧「对账」图标（替代原「全部对账」TextButton）+ 居中「资产管理」标题 + 右侧「新建账户」图标。
- scrim：`listScrolled` 或有 `backgroundUri` 时渐显黑色渐隐（仿首页 `topBarScrimAlpha`）。
- 在 `Box` 内 `LazyColumn` 之上叠加 `AssetsTopBar(...)`。
- 移除原内嵌的「资产管理」Text + 「全部对账」TextButton 行。
- 验证：编译通过，顶栏悬浮可见

### 4c：卡片加毛玻璃
- `TotalAssetsCard`：`.rinkShadow(RoundedCornerShape(15.dp)).clip(...)` 后加 `.hazeEffect(hazeState, HazeMaterials.thin())`（需把 `hazeState` 透传进该 Composable 参数）
- `AccountCard`：同上加 `.hazeEffect(hazeState, HazeMaterials.thin())`
- `AddAccountCard`：**移除**（入口移交 FAB，见 4d）
- 验证：编译通过，卡片呈毛玻璃质感

### 4d：FAB 替代「+新建账户」卡
- 在 `Box` 内底部居中加 FAB（仿首页 FAB：`rinkShadow(CircleShape)` + `clip(CircleShape)` + `hazeEffect(hazeState, HazeMaterials.thin())` + 51dp + `ic_add_bill` 图 + `clickable { addingAccount = true }`）
- 验证：编译通过，点 FAB 弹 AddAccountDialog

### 4e：`PullToRefreshBox` 包裹 + 下拉触发同步（nav 注入回调，VM 不动）
- `LazyColumn` 外包 `PullToRefreshBox`。
- **VM 完全不动**（守设计文档第 5 节"AssetsState/Event 不动"硬约束）。`AssetsScreen` 签名再加 `onRefresh: () -> Unit` 参数；nav 层传 `{ app.syncManager.sync() }`。
- `isRefreshing` 用本地状态：`var refreshing by remember { mutableStateOf(false) }`，`onRefresh = { refreshing = true; kotlinx.coroutines.GlobalScope 或 LaunchedEffect 触发 onRefresh() 后置 false }`。最简实现：`onRefresh` 包成 `LaunchedEffect` 协程，fire-and-forget 同步，~1s 后置 false 兜底动画。
- 验证：编译通过，下拉触发同步动画

---

## 任务 5：`PlanScreen.kt` 重构

### 5a：签名扩展 + 根背景 + 首项垫高
- 改 `fun PlanScreen(viewModel: BudgetViewModel, isActive: Boolean = true)` →
  `fun PlanScreen(viewModel: BudgetViewModel, isActive: Boolean = true, backgroundUri: String?, hazeState: HazeState)`
- 根 `Box` 内层 `Column` 之前：无 `backgroundUri` 时铺 `DefaultHazeBackground(hazeState)`
- `Column` 首项加 `Spacer(height = topBarHeight)`
- 移除原内嵌「计划」Text 标题（移交 5b 顶栏）
- 验证：编译通过（配合任务 3）

### 5b：新增悬浮 `PlanTopBar`（极简）
- 居中「计划」标题；左右两侧空（预算页无额外操作，tab 内页无返回键）
- scrim 同首页逻辑
- 验证：编译通过

### 5c：卡片加毛玻璃
- `TotalBudgetCard` / `CategoryBudgetCard` / `SubCategoryBudgetRow`：`rinkShadow + clip` 后加 `.hazeEffect(hazeState, HazeMaterials.thin())`（透传 `hazeState`）
- `BudgetKeypadOverlay`：保持现状（Motion + BackHandler 已对齐，不动）
- 验证：编译通过 + `BudgetViewModelTest` 仍绿

---

## 任务 6：验收

- `:app:test` 全绿（含新 `AssetsViewModelTest` 7 用例 + 原 `BudgetViewModelTest` 等）
- `:app:assembleDebug` 编译通过
- 风格清单人工核对（UI 层无自动测试，明确为人工验证点）：
  - [ ] 资产页：渐变背景 / TotalAssetsCard+AccountCard 毛玻璃 / 悬浮 AssetsTopBar+scrim / FAB / BalanceEditDialog Motion 上滑 / 下拉刷新
  - [ ] 预算页：渐变背景 / TotalBudgetCard+CategoryBudgetCard 毛玻璃 / 悬浮 PlanTopBar / BudgetKeypadOverlay Motion 上滑
  - [ ] 首页：抽公共 DefaultHazeBackground 后无回归
  - [ ] 业务：账户增删改/对账/全部对账、预算三层设置仍可用

---

## 执行顺序（依赖关系）

任务 3、4a、5a 互相依赖（签名 + nav 调用要一起编译），建议：
1. 任务 1（AssetsViewModelTest）→ 跑测试确认绿基线
2. 任务 2（DefaultHazeBackground 抽公共）→ 编译
3. 任务 4a + 5a + 3 一起改签名 + nav 透传 → 编译
4. 任务 4b-4e（AssetsScreen 逐子任务）→ 边改边编译
5. 任务 5b-5c（PlanScreen 逐子任务）→ 边改边编译
6. 任务 6 验收

每完成一个子任务跑 `:app:assembleDebug`（编译快），完成一个页面跑 `:app:test`（回归）。
