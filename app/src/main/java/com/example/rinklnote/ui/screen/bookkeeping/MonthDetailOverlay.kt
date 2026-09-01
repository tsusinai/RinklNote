package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.rinklnote.ui.theme.Motion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.theme.Blue80
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 月度明细筛选状态：无 | 只看某天 | 只看某分类。热力图/饼图始终显示整月，仅下方明细跟随收窄。 */
private sealed interface MonthFilter {
    data object None : MonthFilter
    data class Day(val day: Int) : MonthFilter
    data class Category(val name: String) : MonthFilter
}

/** 饼图切片：分类名 + 金额 + 占比(0..1)。 */
private data class PieSlice(val name: String, val amount: Double, val pct: Float)

/** 单月汇总数据（不可变种子，随 bills 变化重算一次）。 */
private data class MonthSummary(
    val expenseTotal: Double,
    val incomeTotal: Double,
    val dayAmounts: Map<Int, Double>,
    val pieSlices: List<PieSlice>
)

/** 饼图配色：主蓝起头，末位灰色固定给「其他」。 */
private val PiePalette = listOf(
    Color(0xFF7EC1FC),
    Color(0xFFF97D1D),
    Color(0xFF04A433),
    Color(0xFF9B59B6),
    Color(0xFFF2B134),
    Color(0xFFCA3032),
    Color(0xFFB0B0B0)
)

@Composable
fun MonthDetailOverlay(
    visible: Boolean,
    monthLabel: String,
    bills: List<Bill>,
    month: LocalDate,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
    ) {
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

            // 汇总与分组只在 bills 变化时算一次，避免每次重组重新 filter/groupBy/sum
            val summary = remember(bills) {
                val expenseTotal = bills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }
                val incomeTotal = bills.filter { it.billType == "INCOME" }.sumOf { it.amount }
                val dayAmounts = bills.filter { it.billType == "EXPENSE" }
                    .groupBy { Instant.ofEpochMilli(it.date).atZone(bookkeepingZone()).toLocalDate().dayOfMonth }
                    .mapValues { it.value.sumOf { b -> b.amount } }
                MonthSummary(expenseTotal, incomeTotal, dayAmounts, buildPieSlices(bills.filter { it.billType == "EXPENSE" }))
            }

            // 选中筛选；切换可见/换月时自动重置为「无」
            var filter by remember(visible, bills) { mutableStateOf<MonthFilter>(MonthFilter.None) }

            // 明细清单：跟随筛选收窄，否则整月
            val filteredBills = remember(bills, filter) {
                val current = filter
                when (current) {
                    MonthFilter.None -> bills
                    is MonthFilter.Day -> bills.filter {
                        Instant.ofEpochMilli(it.date).atZone(bookkeepingZone()).toLocalDate().dayOfMonth == current.day
                    }
                    is MonthFilter.Category -> bills.filter { it.categoryName == current.name }
                }
            }
            val filteredGroup = remember(filteredBills) {
                filteredBills.groupBy { it.categoryName }
                    .map { (name, bl) -> Triple(name, bl.sumOf { it.amount }, bl) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                item(key = "totals") { TotalsCard(summary.expenseTotal, summary.incomeTotal) }
                if (filter != MonthFilter.None) {
                    item(key = "filter") { FilterBanner(filter) { filter = MonthFilter.None } }
                }
                item(key = "heatmap") {
                    MonthHeatmap(
                        month = month,
                        dayAmounts = summary.dayAmounts,
                        selectedDay = (filter as? MonthFilter.Day)?.day,
                        onDayTap = { day ->
                            filter = if (filter == MonthFilter.Day(day)) MonthFilter.None else MonthFilter.Day(day)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                item(key = "pie") {
                    ExpensePie(
                        slices = summary.pieSlices,
                        selectedCategory = (filter as? MonthFilter.Category)?.name,
                        onCategoryTap = { name ->
                            filter = if (filter == MonthFilter.Category(name)) MonthFilter.None else MonthFilter.Category(name)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (filteredBills.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("该月暂无账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                filteredGroup.forEach { (catName, subtotal, catBills) ->
                    item(key = "cat_$catName") {
                        CategoryHeader(catName, subtotal)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    items(catBills, key = { it.id }) { bill ->
                        DetailRow(bill)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** 单月支出按分类占比 → 切片；>6 类时取前 5 + 「其他」，小项归一。 */
private fun buildPieSlices(expense: List<Bill>): List<PieSlice> {
    val total = expense.sumOf { it.amount }
    if (total <= 0.0) return emptyList()
    val byCategory = expense.groupBy { it.categoryName }
        .mapValues { it.value.sumOf { b -> b.amount } }
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

    val keep: List<Pair<String, Double>> = if (byCategory.size <= 6) {
        byCategory
    } else {
        byCategory.take(5) + ("其他" to byCategory.drop(5).sumOf { it.second })
    }
    return keep.map { (name, amount) -> PieSlice(name, amount, (amount / total).toFloat()) }
}

/** 4x8 矩阵热力图：黑白两对角，主蓝透明度随当日支出金额加深；点击网格 → 筛当天。 */
@Composable
private fun MonthHeatmap(
    month: LocalDate,
    dayAmounts: Map<Int, Double>,
    selectedDay: Int?,
    onDayTap: (Int) -> Unit
) {
    val daysInMonth = month.lengthOfMonth()
    val maxAmount = dayAmounts.values.maxOrNull() ?: 0.0
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("每日支出热力图", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        repeat(4) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(8) { col ->
                    val day = row * 8 + col + 1
                    if (day > daysInMonth) {
                        // 月外占位格——保持网格不塌缩，不可点击
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val amount = dayAmounts[day] ?: 0.0
                        val alpha = if (maxAmount > 0) (amount / maxAmount).toFloat() else 0f
                        val fill = if (amount > 0) {
                            Blue80.copy(alpha = 0.15f + 0.85f * alpha)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                        val isSelected = selectedDay == day
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(fill)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, primary, RoundedCornerShape(6.dp))
                                    else Modifier
                                )
                                .clickable { onDayTap(day) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$day",
                                fontSize = 10.sp,
                                color = if (amount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/** 饼图：切片按占比画扇区（内标 %），下方图例列「分类 ¥金额 · 占比」；点切片/图例 → 筛该分类。 */
@Composable
private fun ExpensePie(
    slices: List<PieSlice>,
    selectedCategory: String?,
    onCategoryTap: (String) -> Unit
) {
    if (slices.isEmpty()) {
        Text("本月暂无支出", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text("支出分类占比", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(8.dp))

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 9.sp)
    // 提前测量各切片百分比文案；小切片(<6%)不标，避免挤
    val layouts = slices.map {
        if (it.pct >= 0.06f) textMeasurer.measure(AnnotatedString("${(it.pct * 100).roundToInt()}%"), labelStyle) else null
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        val radius = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = slice.pct * 360f
            drawArc(
                color = PiePalette[index % PiePalette.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2)
            )
            layouts[index]?.let { layout ->
                val midAngle = startAngle + sweep / 2f
                val rad = Math.toRadians(midAngle.toDouble())
                val labelR = radius * 0.6f
                drawText(
                    layout,
                    topLeft = Offset(
                        cx + (cos(rad) * labelR).toFloat() - layout.size.width / 2f,
                        cy + (sin(rad) * labelR).toFloat() - layout.size.height / 2f
                    )
                )
            }
            startAngle += sweep
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    slices.forEachIndexed { index, slice ->
        val isSelected = selectedCategory == slice.name
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent)
                .clickable { onCategoryTap(slice.name) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(PiePalette[index % PiePalette.size])
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(slice.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "¥${String.format("%.2f", slice.amount)} · ${(slice.pct * 100).roundToInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 顶部筛选横幅：显示「已筛选：第X天 / 分类名」+ 可点「清除」。 */
@Composable
private fun FilterBanner(filter: MonthFilter, onClear: () -> Unit) {
    val label = when (filter) {
        MonthFilter.None -> ""
        is MonthFilter.Day -> "第${filter.day}天"
        is MonthFilter.Category -> filter.name
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("已筛选：$label", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) {
            Text("清除", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TotalsCard(expenseTotal: Double, incomeTotal: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${String.format("%.2f", expenseTotal)}", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("收入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${String.format("%.2f", incomeTotal)}", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = IncomeGreen)
        }
    }
}

@Composable
private fun CategoryHeader(categoryName: String, subtotal: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(categoryName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text("¥${String.format("%.2f", subtotal)}", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun DetailRow(bill: Bill) {
    val isExpense = bill.billType == "EXPENSE"
    val localDate = Instant.ofEpochMilli(bill.date).atZone(bookkeepingZone()).toLocalDate()
    val dateLabel = "${localDate.monthValue}月${localDate.dayOfMonth}日"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = dateLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = bill.remark ?: (bill.subCategoryName ?: bill.categoryName),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = (if (isExpense) "-" else "+") + String.format("%.2f", bill.amount),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
        )
    }
}
