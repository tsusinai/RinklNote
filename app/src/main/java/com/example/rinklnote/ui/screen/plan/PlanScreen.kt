package com.example.rinklnote.ui.screen.plan

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.viewmodel.BudgetEditTarget
import com.example.rinklnote.ui.viewmodel.BudgetEvent
import com.example.rinklnote.ui.viewmodel.BudgetState
import com.example.rinklnote.ui.viewmodel.BudgetViewModel
import com.example.rinklnote.ui.viewmodel.CategoryBudgetState
import com.example.rinklnote.ui.viewmodel.SubCategoryBudgetState
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlin.math.abs

/**
 * 计划/预算页（Plan）—— 月度预算管理。
 *
 * 风格深度对齐首页（Bookkeeping）：
 * - `Box` 根 + 渐变背景作毛玻璃（haze）blur 源；自选照片时由 nav 层整窗铺满。
 * - 悬浮顶栏（floating top bar）：极简，仅居中「计划」标题（tab 内页无返回键，左右留空）+ scrim 渐隐。
 * - 卡片 `rinkShadow` + 毛玻璃 + 圆角；子分类行不加毛玻璃避免嵌套怪异。
 *
 * 编辑流：点击总额卡/分类行/子分类行 → 派发 [BudgetEvent.EditBudget] 填充共享编辑状态 →
 * [onEditBudget] 跳转独立路由 `budget-edit`（bill-edit 同款），金额输入/删除都在编辑页完成。
 *
 * @param viewModel 预算页 ViewModel
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺渐变
 * @param hazeState nav 层透传的毛玻璃状态
 * @param onEditBudget 跳转预算编辑页（导航层注入）
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PlanScreen(
    viewModel: BudgetViewModel,
    backgroundUri: String?,
    hazeState: HazeState,
    onEditBudget: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                onClick = {
                    viewModel.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Total))
                    onEditBudget()
                }
            )

            // 上月结余（仅展示，不结转）
            state.lastMonthSurplusMinor?.let { surplus ->
                Spacer(modifier = Modifier.height(10.dp))
                LastMonthSurplusRow(surplusMinor = surplus)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分类/子分类分层预算列表
            if (state.categoryBudgets.isEmpty()) {
                EmptyCategoryGuide(onClick = {
                    viewModel.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Total))
                    onEditBudget()
                })
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
                            onClick = {
                                viewModel.onEvent(
                                    BudgetEvent.EditBudget(
                                        BudgetEditTarget.Category(category.categoryId, category.categoryName)
                                    )
                                )
                                onEditBudget()
                            },
                            onSubClick = { sub ->
                                viewModel.onEvent(
                                    BudgetEvent.EditBudget(
                                        BudgetEditTarget.SubCategory(
                                            subCategoryId = sub.subCategoryId,
                                            subCategoryName = sub.name,
                                            parentCategoryId = sub.parentCategoryId,
                                            parentCategoryName = category.categoryName
                                        )
                                    )
                                )
                                onEditBudget()
                            }
                        )
                    }
                }
            }
        }

        // 悬浮顶栏：居中「计划」标题 + scrim（对齐首页 TopBar，但预算页无额外操作，左右留空）。
        PlanTopBar(
            scrimAlpha = topBarScrimAlpha,
            hasBackground = backgroundUri != null,
            listScrolled = listScrolled
        )
    }
}

/** 预算页悬浮顶栏：极简，仅居中「计划」标题 + 渐隐 scrim（tab 内页无返回键，左右留空）。 */
@Composable
private fun PlanTopBar(scrimAlpha: Float, hasBackground: Boolean, listScrolled: Boolean, modifier: Modifier = Modifier) {
    // 文字色三态：有背景→白；无背景→自定义主题「顶栏标题色」（默认=字体色），滚动后略淡
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
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
                color = textColor,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * 本月总额预算卡（Total Budget Card）：白底 + 毛玻璃。
 *
 * - 未设预算：显示「设置」入口；点击跳预算编辑页。
 * - 已设：预算额 + 进度条（`LinearProgressIndicator`）+ 已花/预算 + 百分比 + 剩余天数；
 *   超预算时进度条转 error 色并显示超额金额。
 */
@Composable
private fun TotalBudgetCard(
    state: BudgetState,
    onClick: () -> Unit
) {
    val budget = state.totalBudget
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
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
                // 未设预算：主色「设置 ›」入口，比原纯文本更有点击暗示。
                Text("设置 ›", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    text = Money.format(budget.amountMinor),
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
            val spentText = Money.format(state.monthExpenseMinor)
            val budgetText = Money.format(budget.amountMinor)
            val percent = if (budget.amountMinor > 0) (state.monthExpenseMinor.toDouble() / budget.amountMinor * 100).toInt() else 0

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

            // 已花/预算与百分比左右分列：百分比独立加粗，超支时转 error 色更醒目。
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "已花 $spentText / 预算 $budgetText",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$percent%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "本月剩余 ${state.remainingDays} 天",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (over) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "已超预算 ${Money.format(state.overTotalBy)}",
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
private fun LastMonthSurplusRow(surplusMinor: Long) {
    val sign = if (surplusMinor >= 0) "+" else "-"
    val color = if (surplusMinor >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "上月结余 $sign${Money.format(abs(surplusMinor))}（仅展示）",
            fontSize = 13.sp,
            color = color
        )
    }
}

/** 无分类预算时的空态引导（empty state guide）：点击直达总额预算编辑。 */
@Composable
private fun EmptyCategoryGuide(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有任何预算",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "点击设置本月总额预算，或点分类单独设",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
@Composable
private fun CategoryBudgetCard(
    category: CategoryBudgetState,
    onClick: () -> Unit,
    onSubClick: (SubCategoryBudgetState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(13.dp))
            .clip(RoundedCornerShape(13.dp))
            .then(applyCardGlass(RoundedCornerShape(13.dp)))
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
                    expenseMinor = category.expenseMinor,
                    amountMinor = category.amountMinor,
                    progress = category.progress,
                    over = category.isOverBudget,
                    overBudgetBy = category.overBudgetBy
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (category.amountMinor > 0) {
                Text(
                    text = currencyText(category.amountMinor),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (category.isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            } else {
                // 未设预算：主色调小胶囊引导（比原灰字「未设」更醒目、更有可点暗示）。
                Text(
                    text = "未设 · 去设置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
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
            expenseMinor = sub.expenseMinor,
            amountMinor = sub.amountMinor,
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
    expenseMinor: Long,
    amountMinor: Long,
    progress: Float,
    over: Boolean,
    overBudgetBy: Long,
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
    val spentText = Money.format(expenseMinor)
    if (over) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "已花 $spentText / 预算 ${currencyText(amountMinor)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "已超 ${Money.format(overBudgetBy)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error
            )
        }
    } else if (amountMinor > 0) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "已花 $spentText / 预算 ${currencyText(amountMinor)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${(expenseMinor.toDouble() / amountMinor * 100).toInt()}%",
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
private fun currencyText(value: Long): String =
    Money.toYuanInputString(value)

