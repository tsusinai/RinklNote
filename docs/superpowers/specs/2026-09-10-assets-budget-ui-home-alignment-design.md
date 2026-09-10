# 资产页与预算页 UI 重构 · 对齐首页风格（设计文档）

> 日期：2026-09-10 ｜ 方法论：superpowers ｜ 状态：待用户确认
> 范围：`AssetsScreen.kt` + `PlanScreen.kt` + `AppNavigation.kt`（透传）
> 不动：业务层（State/Event/ViewModel/Repository/同步语义）

## 1. 目标

把**资产页**与**预算页**的视觉与交互语言深度对齐到**首页（BookkeepingScreen）**，消除当前两页与首页的风格断层（平面背景、无毛玻璃、无悬浮顶栏）。用户已确认范围 = 资产 + 预算两页，深度 = 深度对齐。

## 2. 首页风格基准（BookkeepingScreen.kt 现状）

| # | 特征 | 实现位置 |
|---|------|----------|
| 1 | 根 `Box(fillMaxSize)` | `BookkeepingScreen` |
| 2 | 背景：有 `backgroundUri` → nav 层 `AppBackground` 铺照片；无 → 本页 `DefaultBackgroundLayer`（`primaryContainer→background` 纵向渐变）作 `hazeSource` | `DefaultBackgroundLayer`（当前 private） |
| 3 | 悬浮 `TopBar`：`statusBarsPadding` + 白色图标 + 月份切换 + 渐隐黑色 scrim（`listScrolled` 或有照片时显现） | `TopBar` |
| 4 | 卡片：`rinkShadow` + `RoundedCornerShape(15dp)` + `clip` + `hazeEffect(HazeMaterials.thin())` | `SummaryBar`/`BillCard`/`HeatmapBox` |
| 5 | `LazyColumn` + `item(key=)` + `Spacer(10dp)` 间隔 | 主列表 |
| 6 | overlay 上滑：`Motion.SheetEnter/SheetExit` | `BillEditOverlay` |
| 7 | FAB：`rinkShadow(CircleShape)` + `hazeEffect` + 51dp | 加号浮球 |
| 8 | `PullToRefreshBox` 下拉刷新 | 主列表外层 |

## 3. 现状差距

### 资产页（AssetsScreen.kt）
- 根是 `Column + statusBarsPadding + 16dp padding`（非 Box）
- 无背景渐变 / 无 `hazeSource` / 无悬浮顶栏（只有内嵌「资产管理」Text + 「全部对账」TextButton 行）
- `TotalAssetsCard`：纯 `primary` 色块，无毛玻璃
- `AccountCard`：有 `rinkShadow` ✓ 但**无 `hazeEffect`**
- `AddAccountCard`：`surfaceVariant` 色块
- `BalanceEditDialog` overlay **已用 Motion** ✓（这一处已对齐）
- 无 FAB、无下拉刷新

### 预算页（PlanScreen.kt）
- 根 `Box` + 内层 `Column`
- 无背景渐变 / 无 `hazeSource` / 无悬浮顶栏（内嵌「计划」Text）
- `TotalBudgetCard`/`CategoryBudgetCard`：有 `rinkShadow` ✓ 但**无 `hazeEffect`**
- `BudgetKeypadOverlay` **已用 Motion + BackHandler** ✓（已对齐）
- 无 FAB、无下拉刷新

## 4. 设计方案（深度对齐）

### 改动 1：AppNavigation 透传 HazeState + backgroundUri

```kotlin
composable("assets") {
    AssetsScreen(
        viewModel = assetsVM,
        backgroundUri = appBackgroundUri,
        hazeState = hazeState
    )
}
composable("plan") {
    PlanScreen(
        viewModel = budgetVM,
        isActive = currentRoute == "plan",
        backgroundUri = appBackgroundUri,
        hazeState = hazeState
    )
}
```

签名与 `BookkeepingScreen` 对齐：`backgroundUri: String?, hazeState: HazeState` 在最后两个位置。

### 改动 2：抽公共 `DefaultBackgroundLayer`

首页里 `DefaultBackgroundLayer` 是 `private`。三页共用，抽到 `ui/component/HazeBackground.kt`（或并入 `AppBackground.kt`）作公共 `@Composable`：

```kotlin
@Composable
fun DefaultHazeBackground(hazeState: HazeState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()
        .background(Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.primaryContainer,
                  MaterialTheme.colorScheme.background)))
        .hazeSource(hazeState))
}
```

首页 `BookkeepingScreen.DefaultBackgroundLayer` 改为调用此公共函数（去重，DRY）。

### 改动 3：AssetsScreen 重构

- 根改 `Box(fillMaxSize)`
- 无 `backgroundUri` 时 `DefaultHazeBackground(hazeState)`；有则 nav 层已铺照片，本页不重复
- 内层 `LazyColumn`，首项 `Spacer(topBarHeight)`（与首页一致：`statusBars top + 46dp`）
- **新增悬浮 `AssetsTopBar`**：`statusBarsPadding` + 左侧「对账」图标（替代原「全部对账」TextButton）+ 居中「资产管理」标题 + 右侧「新建账户」图标；scrim 同首页
- `TotalAssetsCard`：`rinkShadow` + `clip(15dp)` + `hazeEffect(thin)` + `primary` 仍作强调（毛玻璃采样渐变背景）
- `AccountCard`：保留 `rinkShadow`，叠 `hazeEffect(thin)`
- 移除 `AddAccountCard`（入口移交 FAB 或顶栏右侧）
- **FAB**：新建账户（样式与首页 FAB 一致：`rinkShadow(CircleShape)` + `hazeEffect` + 51dp + `ic_add_bill` 同款加号图）
- `BalanceEditDialog` overlay 保持 Motion（已对齐，不动）
- `PullToRefreshBox` 包 `LazyColumn`，`onRefresh` 触发 `syncManager.sync()`（需 `AssetsViewModel` 暴露 `refresh()` 或直接由 nav 层 `app.syncManager.sync()` —— 推荐后者，VM 不增事件）

### 改动 4：PlanScreen 重构

- 根 `Box` 已有
- 无 `backgroundUri` 时 `DefaultHazeBackground`
- 内层 `Column` 首项 `Spacer(topBarHeight)`
- **新增悬浮 `PlanTopBar`**：居中「计划」标题；预算页无额外操作，顶栏极简（左侧空、右侧空，仅标题居中 + scrim）
- `TotalBudgetCard`/`CategoryBudgetCard`/`SubCategoryBudgetRow`：`rinkShadow` + 叠 `hazeEffect(thin)`
- `BudgetKeypadOverlay` 保持 Motion + BackHandler（已对齐，不动）
- **不加 FAB**（点卡片即出键盘，FAB 冗余）
- **不加下拉刷新**（数据是 `combine(budgets, bills, categories, subCategories)` 双流实时合流，下拉无新数据可拉）

## 5. 不变量（硬约束）

- `AssetsState`/`AssetsEvent`、`BudgetState`/`BudgetEvent`、`CategoryBudgetState`、`SubCategoryBudgetState` 全不动
- `AssetsViewModel`/`BudgetViewModel` 业务逻辑不动（除按需加 `refresh()` 入口）
- 两段式确认（`BalanceEditDialog` 确认流、`BudgetKeypadOverlay` 确认流）不简化
- 隐私 NFR：资产/预算页不送 LLM，不涉及
- `Motion.SheetEnter/SheetExit`、`rinkShadow`、`HazeMaterials.thin()` 复用
- 金额仍是 `Double`（不动已知债务）
- `accountIconRes` / `ACCOUNT_COLORS` / `hexColor` 等私有 helper 保留

## 6. TDD 策略

> superpowers 强制 TDD。本项目的测试基建现实：
> - JVM 单测（`:app:test`）：JUnit4 + runTest + fake repository，**可跑**（已验证 Gradle 8.13 + JDK 17 启动正常）
> - Compose UI 测试（`androidTest`）：目录存在但**无 `createComposeRule` 基建**，从零搭建成本高且需设备

**务实分层**：

| 层 | 测试方式 | 守护点 |
|----|----------|--------|
| ViewModel（资产） | **新增 `AssetsViewModelTest.kt`**（JVM 单测） | 六事件落库 dirty + `pushAccount` 调用契约，重构期间回归守护 |
| ViewModel（预算） | `BudgetViewModelTest.kt` 已有 | 重构期间跑回归确保不破坏 |
| UI（Composable） | 无自动测试，用「风格清单逐项核对 + `assembleDebug` 编译通过 + 人工目视」 | 视觉对齐首页 |

**TDD 顺序**：
1. 先写 `AssetsViewModelTest.kt`（六事件，仿 `BudgetViewModelTest` 模板）→ 跑 → 应直接绿（VM 行为已存在，测试作回归基线）
2. 重构 `AssetsScreen`/`PlanScreen` UI → 跑 `:app:test` → VM 测试仍绿（证明业务未破坏）
3. `assembleDebug` 编译通过
4. 风格清单逐项核对（人工验证点，明确标注）

## 7. 风险与回滚

- **`hazeEffect` 无 `hazeSource` 时显灰兜底色**：必须保证 `DefaultHazeBackground` 在无照片时先铺（渐变作 source）。改动后每个用 `hazeEffect` 的卡片都能采到源。
- **`topBarHeight` inset 计算**：复用首页算法（`statusBars top + 46dp`），保持一致。
- **资产页 FAB 与原「+新建账户」卡入口重复**：移除卡片、改 FAB，二选一不留双入口。
- **预算页顶栏极简 vs 首页四图标**：接受差异（tab 内页无返回键，标题居中即合规）。
- 回滚：所有改动在隔离分支，不合并不影响 main。

## 8. 验收标准

- [ ] `:app:test` 全绿（含新 `AssetsViewModelTest` 的 6+ 用例）
- [ ] `:app:assembleDebug` 编译通过
- [ ] 资产页视觉与首页一致：渐变背景、毛玻璃卡片（TotalAssets/AccountCard）、悬浮顶栏 + scrim、FAB、Motion overlay
- [ ] 预算页视觉与首页一致：渐变背景、毛玻璃卡片（TotalBudget/CategoryBudget）、悬浮顶栏、Motion overlay
- [ ] 业务行为不变：账户增删改/对账/全部对账、预算三层设置，全部仍可用
- [ ] `DefaultBackgroundLayer` 抽公共后，首页仍正常（去重无回归）

## 9. 待确认决策点（已整合到头脑风暴提问）

设计文档先于确认写出推荐默认；下列点若用户无异议则按推荐执行：
1. 资产页「新建账户」入口 → **FAB**（推荐，与首页一致）
2. 资产页是否加下拉刷新 → **加**（触发同步，风格统一）
3. `DefaultBackgroundLayer` → **抽公共 `DefaultHazeBackground`**（推荐，DRY）
4. 预算页顶栏 → **极简居中标题**（推荐，tab 内页无返回）
