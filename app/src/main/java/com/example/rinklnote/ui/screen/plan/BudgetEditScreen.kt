package com.example.rinklnote.ui.screen.plan

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.BudgetEditState
import com.example.rinklnote.ui.viewmodel.BudgetEditTarget
import com.example.rinklnote.ui.viewmodel.DailySpend
import com.example.rinklnote.ui.viewmodel.SubBreakdown
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.Instant

/**
 * 预算编辑页（独立路由 `budget-edit`）：与账单编辑（BillEditOverlay）共享视觉语言，
 * 承载总额 / 分类 / 子分类三级预算的设置、修改、删除，以及围绕该维度的支出分析与账单联动。
 *
 * 页面结构：顶部操作栏（返回 / 标题 / 删除）→ 滚动内容区：
 * 金额卡（点击弹出自定义数字键盘）→ 环比上月同期 → 日支出趋势 → 支出构成 → 本月相关账单。
 * 键盘为呼出式（非固定底部），确认后不退出页面，分析数据随 Flow 实时刷新。
 *
 * @param state 编辑页状态（target 为 null 时本页不渲染，导航层已保证）
 * @param onConfirm 确认金额（分），由导航层按维度派发 SetBudget；确认后停留本页
 * @param onBillClick 点击相关账单，导航层跳转账单编辑页（bill-edit）
 */
@Composable
fun BudgetEditScreen(
    state: BudgetEditState,
    backgroundUri: String?,
    hazeState: HazeState?,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (Long) -> Unit,
    onBillClick: (Bill) -> Unit
) {
    val target = state.target ?: return
    val hasCustomBackground = backgroundUri != null
    val existing = state.existingAmountMinor

    // 键盘输入中的金额；`null` = 未在输入（大金额卡显示已设金额）。重新呼出键盘时清空。
    var input by remember { mutableStateOf<String?>(null) }
    var showKeypad by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val parsedInput = input?.let { Money.parseMinor(it) }
    val canConfirm = parsedInput != null

    BackHandler {
        if (showKeypad) showKeypad = false else onCancel()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            BudgetEditTopBar(
                title = if (existing == null) "设置预算" else "编辑预算",
                showDelete = existing != null,
                onBack = onCancel,
                onDelete = { showDeleteConfirm = true }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                AmountCard(
                    target = target,
                    input = input,
                    existingAmountMinor = existing,
                    monthExpenseMinor = state.monthExpenseMinor,
                    remainingDays = state.remainingDays,
                    elapsedDays = state.elapsedDays,
                    daysInMonth = state.daysInMonth,
                    hasCustomBackground = hasCustomBackground,
                    onClick = {
                        input = ""
                        showKeypad = true
                    }
                )

                state.prevMonthSamePeriodMinor?.let { prev ->
                    Spacer(modifier = Modifier.height(10.dp))
                    CompareCard(
                        monthExpenseMinor = state.monthExpenseMinor,
                        prevMonthSamePeriodMinor = prev,
                        hasCustomBackground = hasCustomBackground
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                TrendCard(
                    trend = state.dailyTrend,
                    monthExpenseMinor = state.monthExpenseMinor,
                    elapsedDays = state.elapsedDays,
                    hasCustomBackground = hasCustomBackground
                )

                if (state.subBreakdown.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    BreakdownCard(
                        breakdown = state.subBreakdown,
                        hasCustomBackground = hasCustomBackground
                    )
                }

                if (state.recentBills.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    BillsCard(
                        bills = state.recentBills,
                        billCount = state.billCount,
                        hasCustomBackground = hasCustomBackground,
                        onBillClick = onBillClick
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 键盘呼出时先铺半透明遮罩，点遮罩收回键盘。
        if (showKeypad) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showKeypad = false }
            )
        }

        AnimatedVisibility(
            visible = showKeypad,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
        ) {
            NumericKeypad(
                amount = input.orEmpty(),
                billType = "EXPENSE",
                remark = "",
                onDigit = { digit ->
                    val cur = input.orEmpty()
                    input = when {
                        digit == "." -> if (cur.isEmpty()) "0." else if (cur.contains(".")) cur else cur + digit
                        cur == "0" -> digit // 替换前导零
                        else -> cur + digit
                    }
                },
                onClear = { input = "" },
                onBackspace = { input = input.orEmpty().dropLast(1) },
                onToggleType = {},
                showTypeToggle = false,
                showRemark = false,
                confirmEnabled = canConfirm,
                onConfirm = {
                    parsedInput?.let { amount ->
                        onConfirm(amount)
                        showKeypad = false
                    }
                },
                hazeState = hazeState,
                bottomPadding = 16.dp
            )
        }
    }

    if (showDeleteConfirm) {
        DeleteBudgetDialog(
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

/** 维度相关输入提示（与原预算键盘 overlay 文案一致）。 */
private fun hintFor(target: BudgetEditTarget): String = when (target) {
    BudgetEditTarget.Total -> "请输入本月预算金额"
    is BudgetEditTarget.Category -> "请输入「${target.categoryName}」本月预算金额"
    is BudgetEditTarget.SubCategory -> "请输入「${target.subCategoryName}」本月预算金额"
}

/** 维度名：总额 / 分类 / 「父分类 · 子分类」。 */
private fun dimensionLabel(target: BudgetEditTarget): String = when (target) {
    BudgetEditTarget.Total -> "本月总额预算"
    is BudgetEditTarget.Category -> "「${target.categoryName}」本月预算"
    is BudgetEditTarget.SubCategory -> "「${target.parentCategoryName} · ${target.subCategoryName}」本月预算"
}

/** 编辑页顶栏：返回｜标题｜删除（仅已设预算时出现）。 */
@Composable
private fun BudgetEditTopBar(
    title: String,
    showDelete: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "返回" }
        ) {
            Text("返回", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (showDelete) {
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 金额卡（可点击呼出键盘）：维度名 + 状态徽章 + 大金额 + 进度与日均可用/月末预测。
 */
@Composable
private fun AmountCard(
    target: BudgetEditTarget,
    input: String?,
    existingAmountMinor: Long?,
    monthExpenseMinor: Long,
    remainingDays: Int?,
    elapsedDays: Int,
    daysInMonth: Int,
    hasCustomBackground: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val over = existingAmountMinor != null && monthExpenseMinor > existingAmountMinor
    val progress = if (existingAmountMinor != null && existingAmountMinor > 0) {
        (monthExpenseMinor.toDouble() / existingAmountMinor).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(sectionSurface(hasCustomBackground, shape))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dimensionLabel(target),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            StatusChip(existing = existingAmountMinor, over = over)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 大金额：输入中显示原始输入；未输入时显示已设金额（未设显示 ¥0）。
        val displayAmount = when {
            input != null -> input.ifEmpty { "0" }
            existingAmountMinor != null -> Money.format(existingAmountMinor)
            else -> "¥0"
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = displayAmount,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "点击修改 ›",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = hintFor(target),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (existingAmountMinor != null) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            ContextRow(
                label = "本月已花",
                value = "${Money.format(monthExpenseMinor)} / ${Money.format(existingAmountMinor)}（${(progress * 100).toInt()}%）"
            )

            // 日均可用 / 超支 与 月末预测（线性外推：已花 ÷ 已过天数 × 当月天数）。
            val remain = existingAmountMinor - monthExpenseMinor
            if (remain >= 0 && remainingDays != null && remainingDays > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                ContextRow(
                    label = "剩余可用",
                    value = "${Money.format(remain)} · 日均可花 ${Money.format(remain / remainingDays)}"
                )
            }
            if (over) {
                Spacer(modifier = Modifier.height(4.dp))
                ContextRow(
                    label = "已超预算",
                    value = Money.format(monthExpenseMinor - existingAmountMinor),
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
            if (monthExpenseMinor > 0 && elapsedDays > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                ContextRow(
                    label = "预计月末",
                    value = Money.format(monthExpenseMinor / elapsedDays * daysInMonth)
                )
            }
        }
    }
}

/** 预算状态徽章：未设置 / 已超支 / 进行中。 */
@Composable
private fun StatusChip(existing: Long?, over: Boolean) {
    val (text, color, bg) = when {
        existing == null -> Triple(
            "未设置",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
        over -> Triple(
            "已超支",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        )
        else -> Triple(
            "进行中",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    }
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 环比卡：上月同期已花与增减幅度（增加标红，减少/持平标主题色）。 */
@Composable
private fun CompareCard(
    monthExpenseMinor: Long,
    prevMonthSamePeriodMinor: Long,
    hasCustomBackground: Boolean
) {
    SectionCard(title = "环比上月", hint = "上月同期已花", hasCustomBackground = hasCustomBackground) {
        ContextRow(label = "上月同期", value = Money.format(prevMonthSamePeriodMinor))
        Spacer(modifier = Modifier.height(4.dp))
        val diff = monthExpenseMinor - prevMonthSamePeriodMinor
        val pct = if (prevMonthSamePeriodMinor > 0) diff * 100 / prevMonthSamePeriodMinor else null
        val sign = if (diff >= 0) "+" else "-"
        val pctText = pct?.let { p -> "（$sign${kotlin.math.abs(p)}%）" } ?: ""
        ContextRow(
            label = "较上月同期",
            value = "$sign${Money.format(kotlin.math.abs(diff))}$pctText",
            valueColor = if (diff > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

/** 日支出趋势卡：该维度本月每日支出迷你柱状图（1..已过天数），复用无第三方图表约定。 */
@Composable
private fun TrendCard(
    trend: List<DailySpend>,
    monthExpenseMinor: Long,
    elapsedDays: Int,
    hasCustomBackground: Boolean
) {
    SectionCard(
        title = "日支出趋势",
        hint = "本月 1–$elapsedDays 日",
        hasCustomBackground = hasCustomBackground
    ) {
        if (monthExpenseMinor <= 0L) {
            Text(
                text = "本月暂无支出",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            TrendMiniChart(trend = trend)
        }
    }
}

/** 迷你柱状图：最大值取目标数据 max（静态数据，无动画比例漂移问题）。 */
@Composable
private fun TrendMiniChart(trend: List<DailySpend>, modifier: Modifier = Modifier) {
    val maxVal = trend.maxOfOrNull { it.amountMinor }?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    // 边框槽默认透明后，基线改用分割线色（始终可见、随主题）。
    val baselineColor = LocalRinklColors.current.dividerColor
    Canvas(modifier = modifier.fillMaxWidth().height(88.dp)) {
        val n = trend.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barWidth = slot * 0.6f
        val baseline = 1.dp.toPx()
        trend.forEachIndexed { index, day ->
            val barHeight = (day.amountMinor.toFloat() / maxVal) * (size.height - baseline)
            if (barHeight > 0f) {
                drawRoundRect(
                    color = if (index == n - 1) barColor else barColor.copy(alpha = 0.4f),
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - baseline - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }
        }
        drawLine(
            color = baselineColor,
            start = Offset(0f, size.height - baseline),
            end = Offset(size.width, size.height - baseline),
            strokeWidth = baseline
        )
    }
}

/** 构成占比卡：总额维度=一级分类构成；分类维度=子分类构成。 */
@Composable
private fun BreakdownCard(
    breakdown: List<SubBreakdown>,
    hasCustomBackground: Boolean
) {
    SectionCard(title = "支出构成", hint = "按金额排序", hasCustomBackground = hasCustomBackground) {
        val total = breakdown.sumOf { it.amountMinor }.coerceAtLeast(1L)
        breakdown.forEach { item ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = Money.format(item.amountMinor),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${item.amountMinor * 100 / total}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { (item.amountMinor.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 本月相关账单卡：按维度过滤，最近在前；点击账单跳账单编辑页。 */
@Composable
private fun BillsCard(
    bills: List<Bill>,
    billCount: Int,
    hasCustomBackground: Boolean,
    onBillClick: (Bill) -> Unit
) {
    SectionCard(
        title = "本月账单",
        hint = "${billCount} 笔 · 点击编辑",
        hasCustomBackground = hasCustomBackground
    ) {
        bills.forEach { bill ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBillClick(bill) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = billDayLabel(bill),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bill.subCategoryName ?: bill.categoryName,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    bill.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                        Text(
                            text = remark,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "-${Money.format(bill.amountMinor)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        if (billCount > bills.size) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "仅显示最近 ${bills.size} 笔",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun billDayLabel(bill: Bill): String =
    Instant.ofEpochMilli(bill.date).atZone(bookkeepingZone()).let { "${it.monthValue}/${it.dayOfMonth}" }

@Composable
private fun ContextRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 通用卡片：标题 + 右侧提示 + 内容；表面随有无自定义背景切换（对齐 BillEditOverlay）。 */
@Composable
private fun SectionCard(
    title: String,
    hint: String?,
    hasCustomBackground: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(sectionSurface(hasCustomBackground, shape))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            hint?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

/** 卡片表面：有自定义背景时保持透明毛玻璃描边，无背景时实底 + 描边。 */
@Composable
private fun sectionSurface(hasCustomBackground: Boolean, shape: Shape): Modifier =
    if (hasCustomBackground) {
        applyCardGlass(shape)
    } else {
        Modifier
            .background(MaterialTheme.colorScheme.surface, shape)
            .then(applyCardGlass(shape))
    }

@Composable
private fun DeleteBudgetDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条预算？") },
        text = { Text("删除后会从本地和云端同步移除，下月预算不受影响，此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
