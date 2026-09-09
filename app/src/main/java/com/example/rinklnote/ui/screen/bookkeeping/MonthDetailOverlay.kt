package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.rinklnote.ui.theme.Motion
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.MonthDetailData
import com.example.rinklnote.ui.component.MonthChartPager
import com.example.rinklnote.ui.theme.Blue80
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.LocalDate

/** 月度明细筛选状态：无 | 只看某天 | 只看某分类。热力图/图表始终显示整月，仅下方明细跟随收窄。 */
private sealed interface MonthFilter {
    data object None : MonthFilter
    data class Day(val day: Int) : MonthFilter
    data class Category(val name: String) : MonthFilter
}

@Composable
fun MonthDetailOverlay(
    visible: Boolean,
    monthLabel: String,
    bills: List<Bill>,
    month: LocalDate,
    expenseTotal: Double,
    incomeTotal: Double,
    monthDetail: MonthDetailData,
    onDismiss: () -> Unit
) {
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

    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, top = 180.dp, end = 14.dp, bottom = 8.dp)
            ) {
                // 图表（占比|折线|柱状 三段可滑）
                item(key = "chart-pager") {
                    MonthChartPager(
                        month = month,
                        pieSlices = monthDetail.pieSlices,
                        daySeries = monthDetail.daySeries,
                        maxSeries = monthDetail.maxSeries,
                        selectedCategory = (filter as? MonthFilter.Category)?.name,
                        onCategoryTap = { name ->
                            filter = if (filter == MonthFilter.Category(name)) MonthFilter.None else MonthFilter.Category(name)
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(6.dp)) }

                // 每日支出热力图 → 点天筛选
                item(key = "heatmap") {
                    MonthHeatmap(
                        month = month,
                        dayAmounts = monthDetail.dayAmounts,
                        selectedDay = (filter as? MonthFilter.Day)?.day,
                        onDayTap = { day ->
                            filter = if (filter == MonthFilter.Day(day)) MonthFilter.None else MonthFilter.Day(day)
                        }
                    )
                }

                if (filter != MonthFilter.None) {
                    item(key = "filter") { FilterBanner(filter) { filter = MonthFilter.None } }
                }

                item { Spacer(modifier = Modifier.height(6.dp)) }

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

            // 顶部固定层：树形图头部 + 月名/关闭 + 合计金额卡（覆盖在树图上，白底内容在其下方）
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.tree),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = monthLabel,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiary,
                        )
                        TextButton(onClick = onDismiss) {
                            Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    TotalsCard(
                        expenseTotal = expenseTotal,
                        incomeTotal = incomeTotal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
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
        Text("每日支出热力图", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(3.dp))
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
                                .aspectRatio(1.8f)
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
        modifier = Modifier.fillMaxWidth() ,
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
private fun TotalsCard(
    expenseTotal: Double,
    incomeTotal: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            Text("支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${String.format("%.2f", expenseTotal)}", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.tertiary)
        }
        Row {
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
    val isExpense = bill.billType == BillType.EXPENSE
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
