package com.example.rinklnote.ui.screen.plan

import com.example.rinklnote.ui.component.MotionListItem
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.example.rinklnote.ui.component.motionClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.example.rinklnote.ui.component.RinklLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.domain.BudgetRiskLevel
import com.example.rinklnote.ui.component.BurnRiskBar
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.billCategoryIconColor
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.ui.util.subcategoryIconRes
import com.example.rinklnote.ui.viewmodel.BudgetEditTarget
import com.example.rinklnote.ui.viewmodel.BudgetEvent
import com.example.rinklnote.ui.viewmodel.BudgetState
import com.example.rinklnote.ui.viewmodel.BudgetViewModel
import com.example.rinklnote.ui.viewmodel.CategoryBudgetState
import com.example.rinklnote.ui.viewmodel.DailyBudgetAdvice
import com.example.rinklnote.ui.viewmodel.DailyBudgetStatus
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
 * - 总览与分类共用整页滚动；卡片保留主题材质，子分类使用分割线降低嵌套感。
 *
 * 编辑流：点击总额卡/已设预算的分类行/子分类行 → 派发 [BudgetEvent.EditBudget] 填充共享编辑状态 →
 * [onEditBudget] 跳转独立路由 `budget-edit`（bill-edit 同款），金额输入/删除都在编辑页完成。
 * 未设预算的分类行在主页不可点（仅灰色「未设置」纯文本提示），分类预算的新增/编辑统一走
 * 列表区头部的「分类预算设置」入口行 → [onOpenCategoryBudgets] 跳转独立路由
 * `budget-categories`（该路由由主会话接线）。
 *
 * @param viewModel 预算页 ViewModel
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺渐变
 * @param hazeState nav 层透传的毛玻璃状态
 * @param onEditBudget 跳转预算编辑页（导航层注入）
 * @param onOpenCategoryBudgets 进入「分类预算设置」页（导航层注入，路由 `budget-categories`）
 * @param onOpenMonthDetail 超支时「查看支出明细」入口（导航层注入，路由 `month-detail`）
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PlanScreen(
    viewModel: BudgetViewModel,
    backgroundUri: String?,
    hazeState: HazeState,
    onEditBudget: () -> Unit,
    onOpenCategoryBudgets: () -> Unit,
    onOpenMonthDetail: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // 顶栏悬浮：列表首项垫到它下面。
    val topBarHeight = rememberRinklTopBarHeight()
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topBarHeight + 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 总览与分类共用滚动容器，小屏无需在固定总览下挤压列表。
            item(key = "total-budget", contentType = "summary") {
                TotalBudgetCard(
                    state = state,
                    onClick = {
                        viewModel.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Total))
                        onEditBudget()
                    },
                    onOpenMonthDetail = onOpenMonthDetail
                )
            }

            // 预算-挑战联动预警（Task 4.1）：总额预算烧穿过快 → 橙色提示条（实时派生零存储）。
            state.totalBurnRisk?.takeIf { it.level != BudgetRiskLevel.LOW }?.let { risk ->
                item(key = "burn-risk", contentType = "notice") { BurnRiskBar(risk = risk, periodLabel = "本月") }
            }

            // 上月结余（仅展示，不结转）
            state.lastMonthSurplusMinor?.let { surplus ->
                item(key = "last-surplus", contentType = "notice") { LastMonthSurplusRow(surplusMinor = surplus) }
            }

            // 列表区头部：进入「分类预算设置」独立页的入口行（路由 budget-categories 由主会话接线）。
            item(key = "entry-category-budgets") {
                CategoryBudgetEntryRow(onClick = onOpenCategoryBudgets)
            }
            items(state.categoryBudgets, key = { it.categoryId }, contentType = { "category_budget_card" }) { category ->
                MotionListItem {
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

        // 悬浮顶栏：居中「计划」标题 + scrim（对齐首页 TopBar，但预算页无额外操作，左右留空）。
        PlanTopBar(
            scrimAlpha = { topBarScrimAlpha },
            hasBackground = backgroundUri != null,
            listScrolled = listScrolled
        )
    }
}

/** 预算页悬浮顶栏：极简，仅居中「计划」标题 + 渐隐 scrim（tab 内页无返回键，左右留空）。 */
@Composable
private fun PlanTopBar(scrimAlpha: () -> Float, hasBackground: Boolean, listScrolled: Boolean, modifier: Modifier = Modifier) {
    // 文字色三态：有背景→白；无背景→自定义主题「顶栏标题色」（默认=字体色），滚动后略淡
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
    val surface = MaterialTheme.colorScheme.background
    RinklTopBar(
        scrimAlpha = { if (hasBackground) scrimAlpha() else 0f },
        horizontalPadding = 16.dp,
        modifier = modifier.drawBehind {
            if (!hasBackground) drawRect(surface.copy(alpha = scrimAlpha()))
        }
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

/**
 * 本月总额预算区：透明底，标题、金额与状态文字共用左边线。
 *
 * - 未设预算：显示「设置」入口；点击跳预算编辑页。
 * - 已设：预算额 + 进度条（`LinearProgressIndicator`）+ 已花/预算 + 百分比 + 剩余天数 +
 *   今日额度解释区（[DailyAllowanceBlock]，与预算编辑页「日均可花」同一口径）；
 *   超预算时进度条转 error 色并显示超额金额 + 行动入口（[OverBudgetActions]）。
 * - 预算为零 / 周期结束：给对应中文状态文案，不出现除零或误导性正数。
 */
@Composable
private fun TotalBudgetCard(state: BudgetState, onClick: () -> Unit, onOpenMonthDetail: () -> Unit) {
    val budget = state.totalBudget
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Column(Modifier.fillMaxWidth().motionClickable(
            onClickLabel = if (budget == null) "设置本月预算" else "编辑本月预算", role = Role.Button
        ) { onClick() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本月预算", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                PlanOutlineIcon(R.drawable.ic_plan_wallet)
                Spacer(Modifier.weight(1f))
                Text(if (budget == null) "设置" else "编辑", fontSize = 12.sp, color = primary)
                Spacer(Modifier.width(4.dp))
                PlanOutlineIcon(R.drawable.ic_plan_edit, size = 16.dp)
            }
            Spacer(Modifier.height(14.dp))
            Text(budget?.let { Money.format(it.amountMinor) } ?: "尚未设置",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            if (budget == null) {
                Text("设定月度额度，让每笔支出更有计划", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                BudgetMetric("本月已花", Money.format(state.monthExpenseMinor), MaterialTheme.colorScheme.onSurface)
            } else {
                val over = state.isOverTotal
                val progressColor = if (over) MaterialTheme.colorScheme.error else primary
                LinearProgressIndicator(progress = { state.totalProgress }, color = progressColor,
                    trackColor = primary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BudgetMetric("已花", Money.format(state.monthExpenseMinor), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    BudgetMetric(if (over) "已超预算" else "剩余可用",
                        Money.format(if (over) state.overTotalBy else (budget.amountMinor-state.monthExpenseMinor).coerceAtLeast(0)),
                        progressColor, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("本月剩余 ${state.remainingDays} 天", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    PlanOutlineIcon(R.drawable.ic_plan_calendar, size = 16.dp)
                    Spacer(Modifier.weight(1f))
                    if (budget.amountMinor > 0) Text("已用 ${(state.monthExpenseMinor.toDouble()/budget.amountMinor*100).toInt()}%",
                        fontSize = 12.sp, color = progressColor)
                }
                val advice = state.dailyAdvice
                when (advice.status) {
                        DailyBudgetStatus.OK -> { Spacer(Modifier.height(14.dp)); DailyAllowanceBlock(advice) }
                        DailyBudgetStatus.ZERO_BUDGET -> { Spacer(Modifier.height(12.dp));
                            Text("本月预算为 0，调整预算后可查看今日建议额度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DailyBudgetStatus.PERIOD_ENDED -> { Spacer(Modifier.height(12.dp));
                            Text("本月周期已结束，月初设置新预算后可查看今日建议额度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else -> Unit
                }
            }
        }
        // 行动按钮与总览点击区并列，超支时仍可直接查看明细。
        if (state.isOverTotal) { Spacer(Modifier.height(12.dp)); OverBudgetActions(onOpenMonthDetail, onClick) }
    }
}

/** 金额分列后各占独立宽度，避免标签和长金额抢占同一行。 */
@Composable
private fun BudgetMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 计划页操作图标统一为 1.75 单位圆头线条，尊重图标主题槽位。 */
@Composable
private fun PlanOutlineIcon(iconRes: Int, size: Dp = 20.dp) {
    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(size),
        tint = LocalRinklColors.current.iconButtonColor ?: MaterialTheme.colorScheme.primary)
}

/**
 * 今日额度解释块（Daily Allowance Block）：建议额度为主行（primary 槽位高亮），
 * 副行给出计算依据「剩余可用 ÷ 剩余天数」。底色走主题 primary 低透明度，无硬编码色值。
 */
@Composable
private fun DailyAllowanceBlock(advice: DailyBudgetAdvice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "今日建议额度",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = Money.format(advice.dailyAllowanceMinor),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "剩余可用 ${Money.format(advice.remainingMinor)} ÷ ${advice.remainingDays} 天",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 超支行动入口（Over Budget Actions）：「查看支出明细」跳 month-detail、「调整预算」
 * 复用卡片原有 budget-edit 点击流，均沿用现有路由不新造页面。胶囊样式对齐预算编辑页
 * StatusChip（圆角 + 主题色低透明底），支出明细用 error 语义色、调整预算用 primary。
 */
@Composable
private fun OverBudgetActions(
    onOpenMonthDetail: () -> Unit,
    onEditBudget: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OverBudgetActionChip(
            text = "查看支出明细",
            container = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            content = MaterialTheme.colorScheme.error,
            onClick = onOpenMonthDetail,
            modifier = Modifier.weight(1f)
        )
        OverBudgetActionChip(
            text = "调整预算",
            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            content = MaterialTheme.colorScheme.primary,
            onClick = onEditBudget,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 超支行动胶囊：圆角 + 低透明主题底 + 居中文本。 */
@Composable
private fun OverBudgetActionChip(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = content,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .motionClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** 上月结余行（Last Month Surplus）：仅展示，不结转；正数=收入绿，负数 error 色。 */
@Composable
private fun LastMonthSurplusRow(surplusMinor: Long) {
    val sign = if (surplusMinor >= 0) "+" else "-"
    val incomeGreen = LocalRinklColors.current.incomeColor
    val color = if (surplusMinor >= 0) incomeGreen else MaterialTheme.colorScheme.error
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "上月结余 $sign${Money.format(abs(surplusMinor))}（仅展示）",
            fontSize = 14.sp,
            color = color
        )
    }
}

/**
 * 计划模块共用的分类图标（适应铺满）：图标 Box 尺寸即「行内图标位」尺寸，
 * 内部 [Image] 以 `ContentScale.Fit` 铺满整个 Box、不留多余 padding。
 * 分类图标为彩色矢量资源，不走 tint。分类行 / 子分类行 /「分类预算设置」页共用本组件。
 */
@Composable
internal fun PlanCategoryIcon(
    iconRes: Int,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(size)) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 分类区标题与设置入口同排，减少重复卡片；点击整行进入分类预算设置。
 * 点击 → 跳转「分类预算设置」独立页（路由 `budget-categories`，由主会话接线）。
 */
@Composable
private fun CategoryBudgetEntryRow(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().motionClickable(onClick = onClick).defaultMinSize(minHeight = 48.dp)
        .padding(horizontal = 0.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("分类预算", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        PlanOutlineIcon(R.drawable.ic_plan_settings, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text("分类预算设置", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(2.dp))
        PlanOutlineIcon(R.drawable.ic_plan_chevron, size = 16.dp)
    }
}

/**
 * 一级分类预算卡（Category Budget Card）：白底 + 毛玻璃，内含分类行 + 子分类行列表。
 *
 * - 分类行：分类图标（28dp 图标位，[PlanCategoryIcon] 适应铺满）+ 分类名 + 进度条 +
 *   预算额/灰色「未设置」纯文本；已设预算才可点击进编辑，未设行不可点
 *   （R2-A3：主页去掉「未设 · 去设置」引导，新增/编辑统一走「分类预算设置」独立页）。
 * - 子分类行：缩进 + 半透明底色（非毛玻璃），点击进子分类预算键盘。
 *
 * @param onClick 点已设预算的分类行 → 编辑分类预算
 * @param onSubClick 点子分类行 → 编辑子分类预算
 */
@Composable
private fun CategoryBudgetCard(category: CategoryBudgetState, onClick: () -> Unit, onSubClick: (SubCategoryBudgetState) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).then(applyCardGlass(shape)).padding(horizontal = 0.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth().then(
            if (category.amountMinor > 0) Modifier.motionClickable(onClick = onClick) else Modifier
        )) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val tint = LocalRinklColors.current.billCategoryIconColor(category.categoryName, isSystemInDarkTheme())
                Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(categoryIconRes(category.categoryName)), contentDescription = null,
                        tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(category.categoryName, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(if (category.amountMinor > 0) Money.format(category.amountMinor) else "未设置",
                    fontSize = if (category.amountMinor > 0) 15.sp else 12.sp,
                    color = if (category.isOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            }
            Spacer(Modifier.height(12.dp))
            BudgetProgressBar(category.expenseMinor, category.amountMinor, category.progress, category.isOverBudget, category.overBudgetBy)
        }
        category.subBudgets.forEach { sub ->
            Spacer(Modifier.height(12.dp))
            RinklDivider()
            Spacer(Modifier.height(12.dp))
            SubCategoryBudgetRow(sub = sub, iconRes = subcategoryIconRes(category.categoryName, sub.name), onClick = { onSubClick(sub) })
        }
    }
}

/**
 * 子分类预算行（Sub-category Budget Row）：缩进 + 半透明底色，图标 + 名称 + 进度条 + 已花/预算。
 *
 * 不加 `hazeEffect`：嵌在已毛玻璃的 [CategoryBudgetCard] 内，再加一层会让视觉「玻璃套玻璃」怪异。
 */
@Composable
private fun SubCategoryBudgetRow(
    sub: SubCategoryBudgetState,
    iconRes: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp)
            .motionClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 子分类行图标：22dp 图标位，适应铺满。
            PlanCategoryIcon(
                iconRes = iconRes,
                size = 22.dp,
                contentDescription = sub.name
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = sub.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
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
    if (amountMinor > 0) {
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
    }
    val spentText = Money.format(expenseMinor)
    if (over) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "已花 $spentText / 预算 ${currencyText(amountMinor)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
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

/** 金额以整数分输入，展示时统一通过 Money 工具格式化。 */
private fun currencyText(value: Long): String =
    Money.toYuanInputString(value)

