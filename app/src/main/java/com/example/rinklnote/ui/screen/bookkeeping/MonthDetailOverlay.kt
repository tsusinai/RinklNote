package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.MonthDetailData
import com.example.rinklnote.domain.buildMonthDetail
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.BillCategoryIcon
import com.example.rinklnote.ui.component.HeatmapBox
import com.example.rinklnote.ui.component.MonthChartPager
import com.example.rinklnote.ui.component.MonthHeatmap
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.RinklTopBarContentHeight
import com.example.rinklnote.ui.component.motionClickable
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.LocalDate

/** 月度筛选只收窄清单，汇总与图表始终显示整月；再次点选即可解除。 */
private sealed interface MonthFilter {
    data object None : MonthFilter
    data class Day(val day: Int) : MonthFilter
    data class Category(val name: String) : MonthFilter
}

/** 月度详情局部尺度：紧凑汇总、文字标签、无背景分类标题。 */
private object MonthDetailDesign {
    val PagePadding = 20.dp
    val SectionGap = 18.dp
    val Heading = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val Secondary = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val Amount = TextStyle(fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = 0.sp)
}

@Composable
fun MonthDetailOverlay(
    monthLabel: String,
    bills: List<Bill>,
    month: LocalDate,
    expenseTotal: Long,
    incomeTotal: Long,
    monthDetail: MonthDetailData,
    backgroundUri: String?,
    hazeState: HazeState?,
    accounts: List<Account>,
    onSelectMonth: (LocalDate) -> Unit,
    onEditBill: (Bill) -> Unit,
    onBack: () -> Unit
) {
    // 用月份而非账单列表作为重置键，空月之间切换也会清掉旧筛选。
    var filter by remember(month.year, month.monthValue) { mutableStateOf<MonthFilter>(MonthFilter.None) }
    var showMonthPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val filteredBills = remember(bills, filter) {
        when (val current = filter) {
            MonthFilter.None -> bills
            is MonthFilter.Day -> bills.filter {
                Instant.ofEpochMilli(it.date).atZone(bookkeepingZone()).toLocalDate().dayOfMonth == current.day
            }
            is MonthFilter.Category -> bills.filter { it.categoryName == current.name }
        }
    }
    val filteredGroup = remember(filteredBills) { filteredBills.groupBy { it.categoryName } }
    val emptyLabel = when (val current = filter) {
        MonthFilter.None -> "本月暂无账单"
        is MonthFilter.Day -> month.monthValue.toString() + "月" + current.day + "日暂无账单"
        is MonthFilter.Category -> current.name + "暂无账单"
    }

    BackHandler { onBack() }
    LaunchedEffect(month.year, month.monthValue) { listState.scrollToItem(0) }

    Box(Modifier.fillMaxSize().background(
        MaterialTheme.colorScheme.background.copy(alpha = if (backgroundUri == null) 1f else 0.94f)
    )) {
        // 自选背景淡淡透出；无照片时保留共享毛玻璃采样源，正文均用主题令牌。
        if (backgroundUri == null && hazeState != null) DefaultHazeBackground(hazeState)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(
                top = RinklTopBarContentHeight + 6.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
            item(key = "summary", contentType = "header") { MonthlySummary(expenseTotal, incomeTotal) }
            item(key = "chart-pager", contentType = "chart") {
                Spacer(Modifier.height(MonthDetailDesign.SectionGap))
                Text("支出分析", style = MonthDetailDesign.Heading, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = MonthDetailDesign.PagePadding))
                MonthChartPager(
                    month = month,
                    pieSlices = monthDetail.pieSlices,
                    daySeries = monthDetail.daySeries,
                    maxSeries = monthDetail.maxSeries,
                    selectedCategory = (filter as? MonthFilter.Category)?.name,
                    onCategoryTap = { name ->
                        filter = if (filter == MonthFilter.Category(name)) MonthFilter.None else MonthFilter.Category(name)
                    },
                    modifier = Modifier.padding(horizontal = MonthDetailDesign.PagePadding)
                )
            }
            item(key = "heatmap", contentType = "chart") {
                Spacer(Modifier.height(MonthDetailDesign.SectionGap))
                HeatmapBox(
                    heatmap = MonthHeatmap(
                        year = month.year,
                        monthValue = month.monthValue,
                        firstWeekday = month.withDayOfMonth(1).dayOfWeek.value,
                        daysInMonth = month.lengthOfMonth(),
                        dailyExpense = monthDetail.dayAmounts.mapValues { it.value.toFloat() }
                    ),
                    modifier = Modifier.padding(horizontal = MonthDetailDesign.PagePadding),
                    initiallyExpanded = true,
                    selectedDay = (filter as? MonthFilter.Day)?.day,
                    onDayTap = { day ->
                        filter = if (filter == MonthFilter.Day(day)) MonthFilter.None else MonthFilter.Day(day)
                    },
                    compact = true
                )
            }
            item(key = "bill-heading", contentType = "heading") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = MonthDetailDesign.PagePadding)
                        .padding(top = 24.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("账单明细", style = MonthDetailDesign.Heading, color = MaterialTheme.colorScheme.onSurface)
                    Text(filteredBills.size.toString() + "笔", style = MonthDetailDesign.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (filteredBills.isEmpty()) {
                item(key = "empty", contentType = "empty_placeholder") {
                    Box(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 24.dp),
                        contentAlignment = Alignment.Center) {
                        Text(emptyLabel, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            filteredGroup.forEach { (name, categoryBills) ->
                item(key = "category_" + name, contentType = "category-heading") {
                    CategoryHeading(name, categoryBills.sumOf { it.amountMinor })
                }
                // 每笔账单独立复用，不一次性组合整个分类下的长清单。
                items(categoryBills, key = { "bill_" + it.id }, contentType = { "bill-row" }) { bill ->
                    DetailRow(bill, accountNames[bill.accountId], onEditBill)
                }
            }
        }
        MonthDetailTopBar(monthLabel, onBack) { showMonthPicker = true }
    }
    if (showMonthPicker) {
        MonthDetailMonthPicker(month, onDismiss = { showMonthPicker = false }, onSelect = { selected ->
            showMonthPicker = false
            onSelectMonth(selected)
        })
    }
}

/** 头图替换为紧凑收支数字，整数分展示仍统一走 Money。 */
@Composable
private fun MonthlySummary(expenseTotal: Long, incomeTotal: Long) {
    val colors = LocalRinklColors.current
    Column(Modifier.padding(horizontal = MonthDetailDesign.PagePadding)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SummaryAmount("本月支出", expenseTotal, colors.expenseColor, Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(38.dp).background(colors.dividerColor))
            SummaryAmount("本月收入", incomeTotal, colors.incomeColor, Modifier.weight(1f))
        }
        RinklDivider()
    }
}

/** 按实际可用宽度缩放长金额，保证窄屏下两列数字完整显示。 */
@Composable
private fun SummaryAmount(label: String, amount: Long, color: Color, modifier: Modifier) {
    val formatted = Money.format(amount)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val amountStyle = LocalTextStyle.current.merge(MonthDetailDesign.Amount)
    Column(modifier) {
        Text(label, style = MonthDetailDesign.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val availableWidth = (with(density) { maxWidth.roundToPx() } - 2).coerceAtLeast(1)
            val measuredWidth = measurer.measure(formatted, style = amountStyle,
                softWrap = false, maxLines = 1).size.width.coerceAtLeast(1)
            // 合并真实字体与字距再测量；二分字号，避免按比例缩放忽略固定字距而截断数字。
            var fittedSize = 28f
            if (measuredWidth > availableWidth) {
                var low = 12f
                var high = 28f
                repeat(8) {
                    val middle = (low + high) / 2f
                    val width = measurer.measure(formatted, style = amountStyle.copy(fontSize = middle.sp),
                        softWrap = false, maxLines = 1).size.width
                    if (width <= availableWidth) low = middle else high = middle
                }
                fittedSize = low
            }
            Text(formatted, style = amountStyle.copy(fontSize = fittedSize.sp),
                color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 标题整块可点选月份，顶栏沿用应用统一高度和返回行为。 */
@Composable
private fun MonthDetailTopBar(monthLabel: String, onBack: () -> Unit, onMonthClick: () -> Unit) {
    val colors = LocalRinklColors.current
    RinklTopBar(scrimAlpha = 0f, modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.align(Alignment.CenterStart).defaultMinSize(minWidth = 48.dp, minHeight = 46.dp)
            .motionClickable(onClickLabel = "返回", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                tint = colors.topBarTitleColor, modifier = Modifier.size(24.dp))
        }
        Row(Modifier.align(Alignment.Center).defaultMinSize(minHeight = 46.dp)
            .motionClickable(onClickLabel = "切换月份", role = Role.Button, onClick = onMonthClick)
            .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(monthLabel, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.topBarTitleColor)
            Text("▾", fontSize = 12.sp, color = colors.topBarTitleColor.copy(alpha = 0.62f))
        }
    }
}

/** 分类标题无背景，仅用短装饰线建立分组。 */
@Composable
private fun CategoryHeading(name: String, subtotal: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MonthDetailDesign.PagePadding)
        .padding(top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(Money.format(subtotal), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, style = TextStyle(fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(36.dp).height(2.dp)
            .background(LocalRinklColors.current.themeColor, RoundedCornerShape(1.dp)))
    }
}

/** 双行账单：备注或分类为主标题，日期和真实账户为辅助信息，金额沿用收支槽。 */
@Composable
private fun DetailRow(bill: Bill, accountName: String?, onEditBill: (Bill) -> Unit) {
    val colors = LocalRinklColors.current
    val isExpense = bill.billType == BillType.EXPENSE
    val dateLabel = remember(bill.date, accountName) {
        val date = Instant.ofEpochMilli(bill.date).atZone(bookkeepingZone()).toLocalDate()
        date.monthValue.toString() + "月" + date.dayOfMonth + "日" + (accountName?.let { " · " + it } ?: "")
    }
    val title = bill.remark?.takeIf { it.isNotBlank() } ?: bill.subCategoryName ?: bill.categoryName
    val formatted = Money.format(bill.amountMinor)
    Row(Modifier.fillMaxWidth().padding(horizontal = MonthDetailDesign.PagePadding)
        .motionClickable(onClickLabel = "编辑账单", role = Role.Button) { onEditBill(bill) }
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BillCategoryIcon(bill.categoryName, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dateLabel, style = MonthDetailDesign.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text((if (isExpense) "−" else "+") + formatted,
            fontSize = if (formatted.length > 12) 13.sp else 16.sp,
            fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"),
            color = if (isExpense) colors.expenseColor else colors.incomeColor)
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Preview(name = "月度详情 · 浅色账单", showBackground = true)
@Composable
private fun MonthDetailPreview() {
    val month = LocalDate.of(2026, 10, 1)
    val bills = listOf(Bill(id = 1, amountMinor = 4200L, categoryId = 1, categoryName = "三餐",
        accountId = 1, remark = "午餐",
        date = month.plusDays(4).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()))
    RinklNoteTheme {
        MonthDetailOverlay("2026年10月", bills, month, 4200L, 0L, buildMonthDetail(bills, 31),
            null, null, listOf(Account(1, "微信", iconColor = "#7EC1FC")), {}, {}, {})
    }
}

@Preview(name = "月度详情 · 深色空月", showBackground = true)
@Composable
private fun MonthDetailEmptyPreview() {
    RinklNoteTheme(darkTheme = true) {
        MonthDetailOverlay("2026年10月", emptyList(), LocalDate.of(2026, 10, 1), 0L, 0L,
            buildMonthDetail(emptyList(), 31), null, null, emptyList(), {}, {}, {})
    }
}
