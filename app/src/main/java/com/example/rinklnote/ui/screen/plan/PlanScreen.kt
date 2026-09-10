package com.example.rinklnote.ui.screen.plan

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.BudgetEvent
import com.example.rinklnote.ui.viewmodel.BudgetState
import com.example.rinklnote.ui.viewmodel.BudgetViewModel
import com.example.rinklnote.ui.viewmodel.CategoryBudgetState
import com.example.rinklnote.ui.viewmodel.SubCategoryBudgetState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs

/** 预算编辑目标：总额 / 一级分类 / 子分类（点击行时记录，键盘确认后按维度 SetBudget）。 */
private sealed interface BudgetEditTarget {
    data object Total : BudgetEditTarget
    data class Category(val categoryId: Long) : BudgetEditTarget
    data class SubCategory(val subCategoryId: Long, val parentCategoryId: Long) : BudgetEditTarget
}

/**
 * 计划/预算页（Plan）—— 月度预算管理。
 *
 * 风格深度对齐首页（Bookkeeping）：
 * - `Box` 根 + 渐变背景作毛玻璃（haze）blur 源；自选照片时由 nav 层整窗铺满。
 * - 悬浮顶栏（floating top bar）：极简，仅居中「计划」标题（tab 内页无返回键，左右留空）+ scrim 渐隐。
 * - 卡片 `rinkShadow` + `hazeEffect(HazeMaterials.thin())` 毛玻璃 + 圆角；子分类行不加毛玻璃避免嵌套怪异。
 * - 预算键盘 overlay 用上滑 `Motion.SheetEnter/Exit`（与快加键盘一致），而非裸 `if` 硬切换。
 *
 * 业务不变量：ViewModel 的 `State`/`Event` 不动；[BudgetEditTarget] 密封类型表达三级编辑目标（总额/分类/子分类）。
 *
 * @param viewModel 预算页 ViewModel
 * @param isActive 当前 tab 是否激活；离开时收起预算键盘（避免 pager 预组合留存）
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺渐变
 * @param hazeState nav 层透传的毛玻璃状态
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PlanScreen(
    viewModel: BudgetViewModel,
    isActive: Boolean = true,
    backgroundUri: String?,
    hazeState: HazeState
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editTarget by remember { mutableStateOf<BudgetEditTarget?>(null) }

    // 离开「计划」页（横向 pager 滑走/点其他 tab）时收起预算键盘，否则局部 remember 状态
    // 会随 pager 预组合留存，返回时键盘依旧存在（与 QuickAdd 键盘离开记账页被重置一致）。
    LaunchedEffect(isActive) {
        if (!isActive) editTarget = null
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 顶栏悬浮：列表首项垫到它下面。高度 = 状态栏避让 + 标题行（20sp + 上下各 8dp）。
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 46.dp
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "planTopBarScrim"
    )

    // 单层 Box 根：预算键盘必须以页内 overlay 的形式叠在计划内容之上。
    // 之前在 PlanScreen 里把主 Column 和键盘作为两个平级子项直接交给 pager，
    // 键盘虽进入组合但未真正盖住卡片（点击落在平级卡片上、键盘不可见）。
    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页铺主题渐变供各卡片采样。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 顶栏是浮层：首项垫到它下面（不留整块空白）。
            Spacer(modifier = Modifier.height(topBarHeight))

            // 顶部：总额预算卡片
            TotalBudgetCard(
                state = state,
                hazeState = hazeState,
                onClick = { editTarget = BudgetEditTarget.Total }
            )

            // 上月结余（仅展示，不结转）
            state.lastMonthSurplus?.let { surplus ->
                Spacer(modifier = Modifier.height(10.dp))
                LastMonthSurplusRow(surplus = surplus)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分类/子分类分层预算列表
            if (state.categoryBudgets.isEmpty()) {
                EmptyCategoryGuide()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.categoryBudgets, key = { it.categoryId }) { category ->
                        CategoryBudgetCard(
                            category = category,
                            hazeState = hazeState,
                            onClick = { editTarget = BudgetEditTarget.Category(category.categoryId) },
                            onSubClick = { sub ->
                                editTarget = BudgetEditTarget.SubCategory(sub.subCategoryId, sub.parentCategoryId)
                            }
                        )
                    }
                }
            }
        }

        // 悬浮顶栏：居中「计划」标题 + scrim（对齐首页 TopBar，但预算页无额外操作，左右留空）。
        PlanTopBar(scrimAlpha = topBarScrimAlpha)

        // 全屏键盘 overlay 用上滑进入（与快加键盘一致的 SheetEnter/Exit），而非裸 if 硬切换
        AnimatedVisibility(
            visible = editTarget != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
        ) {
            val target = editTarget
            if (target != null) {
                BudgetKeypadOverlay(
                    title = when (target) {
                        BudgetEditTarget.Total -> "设置本月预算"
                        is BudgetEditTarget.Category -> "设置分类预算"
                        is BudgetEditTarget.SubCategory -> "设置子分类预算"
                    },
                    hint = when (target) {
                        BudgetEditTarget.Total -> "请输入本月预算金额"
                        is BudgetEditTarget.Category -> "请输入该分类本月预算金额"
                        is BudgetEditTarget.SubCategory -> "请输入该子分类本月预算金额"
                    },
                    initialAmount = editInitialAmount(state, target),
                    onConfirm = { amount ->
                        editTarget = null
                        viewModel.onEvent(
                            when (target) {
                                BudgetEditTarget.Total -> BudgetEvent.SetBudget(amount)
                                is BudgetEditTarget.Category -> BudgetEvent.SetBudget(amount, categoryId = target.categoryId)
                                is BudgetEditTarget.SubCategory -> BudgetEvent.SetBudget(
                                    amount,
                                    categoryId = target.parentCategoryId,
                                    subCategoryId = target.subCategoryId
                                )
                            }
                        )
                    },
                    onDismiss = { editTarget = null }
                )
            }
        }
    }
}

/** 预算页悬浮顶栏：极简，仅居中「计划」标题 + 渐隐 scrim（tab 内页无返回键，左右留空）。 */
@Composable
private fun PlanTopBar(scrimAlpha: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        // 顶部渐隐遮罩：白色标题下的内容被它压暗，保证可读性。
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.30f * scrimAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "计划",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** 编辑目标当前金额（已有则预填，未设则为空）。 */
private fun editInitialAmount(state: BudgetState, target: BudgetEditTarget): String {
    val amount = when (target) {
        BudgetEditTarget.Total -> state.totalBudget?.amount
        is BudgetEditTarget.Category ->
            state.categoryBudgets.firstOrNull { it.categoryId == target.categoryId }?.amount
        is BudgetEditTarget.SubCategory ->
            state.categoryBudgets
                .firstOrNull { it.categoryId == target.parentCategoryId }
                ?.subBudgets
                ?.firstOrNull { it.subCategoryId == target.subCategoryId }
                ?.amount
    }
    return amount?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: ""
}

/**
 * 本月总额预算卡（Total Budget Card）：白底 + 毛玻璃。
 *
 * - 未设预算：显示「设置」入口；点击进键盘。
 * - 已设：预算额 + 进度条（`LinearProgressIndicator`）+ 已花/预算/百分比 + 剩余天数；
 *   超预算时进度条转 error 色并显示超额金额。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun TotalBudgetCard(
    state: BudgetState,
    hazeState: HazeState,
    onClick: () -> Unit
) {
    val budget = state.totalBudget
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .hazeEffect(hazeState, HazeMaterials.thin())
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("本月预算", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            if (budget == null) {
                Text("设置", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    text = "¥${budget.amount.toBigDecimal().stripTrailingZeros().toPlainString()}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (budget == null) {
            Text("点击设置本月预算", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val over = state.isOverTotal
            val progressColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val spentText = "¥${String.format("%.2f", state.monthExpense)}"
            val budgetText = "¥${String.format("%.2f", budget.amount)}"
            val percent = if (budget.amount > 0) (state.monthExpense / budget.amount * 100).toInt() else 0

            LinearProgressIndicator(
                progress = { state.totalProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "已花 $spentText / 预算 $budgetText ($percent%)",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "本月剩余 ${state.remainingDays} 天",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (over) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "已超预算 ¥${String.format("%.2f", state.overTotalBy)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 上月结余行（Last Month Surplus）：仅展示，不结转；正数 primary 色，负数 error 色。 */
@Composable
private fun LastMonthSurplusRow(surplus: Double) {
    val sign = if (surplus >= 0) "+" else "-"
    val color = if (surplus >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "上月结余 $sign¥${String.format("%.2f", abs(surplus))}（仅展示）",
            fontSize = 13.sp,
            color = color
        )
    }
}

/** 无分类预算时的空态引导（empty state guide）：提示「点击分类设置预算」。 */
@Composable
private fun EmptyCategoryGuide() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "点击分类设置预算",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 32.dp)
        )
    }
}

/**
 * 一级分类预算卡（Category Budget Card）：白底 + 毛玻璃，内含分类行 + 子分类行列表。
 *
 * - 分类行：分类名 + 进度条 + 预算额/未设；点击进分类预算键盘。
 * - 子分类行：缩进 + 半透明底色（非毛玻璃），点击进子分类预算键盘。
 *
 * @param onClick 点分类行 → 编辑分类预算
 * @param onSubClick 点子分类行 → 编辑子分类预算
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun CategoryBudgetCard(
    category: CategoryBudgetState,
    hazeState: HazeState,
    onClick: () -> Unit,
    onSubClick: (SubCategoryBudgetState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(13.dp))
            .clip(RoundedCornerShape(13.dp))
            .hazeEffect(hazeState, HazeMaterials.thin())
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.categoryName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                BudgetProgressBar(
                    expense = category.expense,
                    amount = category.amount,
                    progress = category.progress,
                    over = category.isOverBudget,
                    overBudgetBy = category.overBudgetBy
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (category.amount > 0) currencyText(category.amount) else "未设",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (category.amount > 0) {
                    if (category.isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        category.subBudgets.forEach { sub ->
            SubCategoryBudgetRow(
                sub = sub,
                onClick = { onSubClick(sub) }
            )
        }
    }
}

/**
 * 子分类预算行（Sub-category Budget Row）：缩进 + 半透明底色，名称 + 进度条 + 已花/预算。
 *
 * 不加 `hazeEffect`：嵌在已毛玻璃的 [CategoryBudgetCard] 内，再加一层会让视觉「玻璃套玻璃」怪异。
 */
@Composable
private fun SubCategoryBudgetRow(
    sub: SubCategoryBudgetState,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = sub.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(5.dp))
        BudgetProgressBar(
            expense = sub.expense,
            amount = sub.amount,
            progress = sub.progress,
            over = sub.isOverBudget,
            overBudgetBy = sub.overBudgetBy,
            barHeight = 5.dp,
            compact = true
        )
    }
}

/** 行内金额/支出/进度/超支标红（分类与子分类行共用）。 */
@Composable
private fun BudgetProgressBar(
    expense: Double,
    amount: Double,
    progress: Float,
    over: Boolean,
    overBudgetBy: Double,
    barHeight: androidx.compose.ui.unit.Dp = 6.dp,
    compact: Boolean = false
) {
    val progressColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 5.dp else barHeight)
            .clip(RoundedCornerShape(4.dp)),
        color = progressColor,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    val spentText = "¥${String.format("%.2f", expense)}"
    if (over) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "已花 $spentText / 预算 ${currencyText(amount)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "已超 ¥${String.format("%.2f", overBudgetBy)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error
            )
        }
    } else if (amount > 0) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "已花 $spentText / 预算 ${currencyText(amount)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${(expense / amount * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Text(
            text = "已花 $spentText",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Double → 去尾零的纯数字字符串（无货币符号），如 `12.50` → `"12.5"`。 */
private fun currencyText(value: Double): String =
    value.toBigDecimal().stripTrailingZeros().toPlainString()

/**
 * 预算键盘全屏 overlay（Budget Keypad Overlay）：上滑进入，编辑三级预算目标。
 *
 * - 标题/提示文案按 [BudgetEditTarget] 维度切换（总额/分类/子分类）。
 * - 复用 [NumericKeypad]（与快加键盘同款），隐藏类型切换与备注入口（预算无类型/备注）。
 * - 确认按维度派发 [BudgetEvent.SetBudget]；返回键（[BackHandler]）= 取消。
 *
 * @param initialAmount 已有预算预填，未设则为空串
 * @param onConfirm `(amount)` 回调
 */
@Composable
private fun BudgetKeypadOverlay(
    title: String,
    hint: String,
    initialAmount: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    var amount by remember { mutableStateOf(initialAmount) }

    fun confirmEdit() {
        val value = amount.toDoubleOrNull() ?: return
        onConfirm(value)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = hint,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            NumericKeypad(
                amount = amount,
                billType = "EXPENSE",
                remark = "",
                onDigit = { digit ->
                    amount = when {
                        digit == "." -> if (amount.isEmpty()) "0." else if (amount.contains(".")) amount else amount + digit
                        amount == "0" -> digit // replace leading zero
                        else -> amount + digit
                    }
                },
                onClear = { amount = "" },
                onBackspace = { amount = amount.dropLast(1) },
                onToggleType = {},
                onRemarkClick = {},
                showTypeToggle = false,
                showRemark = false,
                onConfirm = ::confirmEdit
            )
        }
    }
}
