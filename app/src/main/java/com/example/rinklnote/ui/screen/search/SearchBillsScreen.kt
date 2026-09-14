package com.example.rinklnote.ui.screen.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.RinklTopBarContentHeight
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.ZoneOffset

/** 本页卡片统一形状：与首页/月度明细同款 15dp 卡片链。 */
private val SearchCardShape = RoundedCornerShape(15.dp)

/** 搜索框圆角：任务指定的 14dp。 */
private val SearchFieldShape = RoundedCornerShape(14.dp)

/**
 * 「搜索账单」整页（路由 `bill-search` 由主会话接线，非 tab 路由 → 底栏自动隐藏）。
 *
 * 结构：悬浮返回顶栏 + 搜索框（分类/备注/金额）+ 筛选行（种类三段 + 日期模式 + 日期选择）
 * + 汇总条（共 N 笔 · 支出/收入小计）+ 按日分组的 15dp 卡片链；空态「无匹配账单」。
 * 账单行点击 [onEditBill]（主会话接 EditBill 事件 + `bill-edit` 路由）。
 *
 * 背景/材质走项目标准：有自选照片时由 nav 层整窗铺满，本页只铺纯白 haze 源；
 * 卡片 `rinkShadow + clip(15dp) + applyCardGlass`，分割线统一 [RinklDivider]。
 *
 * @param viewModel 由主会话用 `BillSearchViewModel.Factory(app.repository)` 创建
 * @param backgroundUri 自选背景照片 uri；非空 = nav 层已整窗铺满，本页不再自绘背景
 * @param hazeState nav 层毛玻璃状态；null = 无采样源，背景退化为纯色
 * @param onEditBill 行点击回调（主会话接 EditBill + bill-edit）
 * @param onBack 返回
 */
@Composable
fun SearchBillsScreen(
    viewModel: BillSearchViewModel,
    backgroundUri: String?,
    hazeState: HazeState?,
    onEditBill: (Bill) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now(bookkeepingZone()) }

    // 结果按「业务时区的当天」分组（groupBy 保持 encounter 顺序 = 日期倒序）
    val dayGroups = remember(state.results) {
        state.results.groupBy { bill ->
            Instant.ofEpochMilli(bill.date).atZone(bookkeepingZone()).toLocalDate()
        }
    }

    // 日期选择弹窗（按天 1 个、自定义起止 2 个）
    var showDayPicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景层：无自选照片时自铺纯白（并注册毛玻璃采样源）；有照片时不铺底，透出 nav 层 AppBackground。
        if (backgroundUri == null) {
            val hs = hazeState
            if (hs != null) {
                DefaultHazeBackground(hazeState = hs)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                // 顶部垫高 = 统一顶栏高度 + 内容起始留白（对齐月度明细页）。
                top = RinklTopBarContentHeight + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
            item(key = "search-field") {
                SearchField(
                    query = state.query,
                    onQueryChanged = { viewModel.onEvent(BillSearchEvent.QueryChanged(it)) }
                )
            }

            item(key = "filters") {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TypeSegmentRow(
                        selected = state.filter.type,
                        onSelect = { viewModel.onEvent(BillSearchEvent.TypeChanged(it)) }
                    )
                    DateModeSegmentRow(
                        selected = state.filter.dateMode,
                        onSelect = { viewModel.onEvent(BillSearchEvent.DateModeChanged(it)) }
                    )
                    DatePickEntryRow(
                        filter = state.filter,
                        today = today,
                        onDayClick = { showDayPicker = true },
                        onStartClick = { showStartPicker = true },
                        onEndClick = { showEndPicker = true },
                        onMonthStep = { viewModel.onEvent(BillSearchEvent.MonthChanged(it)) },
                        onRangeClear = {
                            viewModel.onEvent(BillSearchEvent.CustomRangeChanged(null, null))
                        }
                    )
                }
            }

            item(key = "summary") {
                SummaryBar(
                    totalCount = state.totalCount,
                    expenseTotal = state.expenseTotal,
                    incomeTotal = state.incomeTotal
                )
            }

            if (state.results.isEmpty()) {
                item(key = "empty") { EmptyResult() }
            }

            dayGroups.forEach { (day, bills) ->
                item(key = "day_${day.toEpochDay()}") {
                    DayGroupCard(
                        day = day,
                        today = today,
                        bills = bills,
                        onEditBill = onEditBill
                    )
                }
            }
        }

        SearchTopBar(
            hasPhoto = backgroundUri != null,
            onBack = onBack
        )
    }

    if (showDayPicker) {
        SearchDatePickerDialog(
            initialDate = state.filter.day ?: today,
            onConfirm = {
                viewModel.onEvent(BillSearchEvent.DayChanged(it))
                showDayPicker = false
            },
            onDismiss = { showDayPicker = false }
        )
    }
    if (showStartPicker) {
        SearchDatePickerDialog(
            initialDate = state.filter.rangeStart ?: today,
            onConfirm = {
                viewModel.onEvent(
                    BillSearchEvent.CustomRangeChanged(it, state.filter.rangeEnd)
                )
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        SearchDatePickerDialog(
            initialDate = state.filter.rangeEnd ?: today,
            onConfirm = {
                viewModel.onEvent(
                    BillSearchEvent.CustomRangeChanged(state.filter.rangeStart, it)
                )
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/** 悬浮返回顶栏：ArrowBack + 居中标题「搜索账单」，与月度明细页同款无边框浮层。 */
@Composable
private fun SearchTopBar(hasPhoto: Boolean, onBack: () -> Unit) {
    val textColor = if (hasPhoto) Color.White else LocalRinklColors.current.topBarTitleColor
    RinklTopBar(
        scrimAlpha = if (hasPhoto) 1f else 0f,
        horizontalPadding = 8.dp
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "搜索账单",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ---------------------------------------------------------------------------
// 搜索框
// ---------------------------------------------------------------------------

/** 搜索框：14dp 圆角玻璃胶囊，前缀放大镜，非空时右侧出现「✕」一键清空。 */
@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(SearchFieldShape)
            .clip(SearchFieldShape)
            .then(applyCardGlass(SearchFieldShape))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜分类 / 备注 / 金额…",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onQueryChanged("") },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 筛选行
// ---------------------------------------------------------------------------

/** 种类三段 toggle：全部 / 支出 / 收入。 */
@Composable
private fun TypeSegmentRow(selected: SearchTypeFilter, onSelect: (SearchTypeFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPill("全部", selected == SearchTypeFilter.ALL) { onSelect(SearchTypeFilter.ALL) }
        FilterPill("支出", selected == SearchTypeFilter.EXPENSE) { onSelect(SearchTypeFilter.EXPENSE) }
        FilterPill("收入", selected == SearchTypeFilter.INCOME) { onSelect(SearchTypeFilter.INCOME) }
    }
}

/** 日期模式切换：全部 / 按天 / 按月 / 自定义（窄屏可横向滚动）。 */
@Composable
private fun DateModeSegmentRow(selected: SearchDateMode, onSelect: (SearchDateMode) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPill("全部", selected == SearchDateMode.ALL) { onSelect(SearchDateMode.ALL) }
        FilterPill("按天", selected == SearchDateMode.DAY) { onSelect(SearchDateMode.DAY) }
        FilterPill("按月", selected == SearchDateMode.MONTH) { onSelect(SearchDateMode.MONTH) }
        FilterPill("自定义起止", selected == SearchDateMode.RANGE) { onSelect(SearchDateMode.RANGE) }
    }
}

/** 筛选胶囊：选中 = 主色浅底 + 主色字，未选 = surfaceVariant 半透明底 + 次要字。 */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** 日期模式对应的第三行选择入口：按天选日 / 按月箭头步进 / 自定义起止两个日期。 */
@Composable
private fun DatePickEntryRow(
    filter: BillSearchFilter,
    today: LocalDate,
    onDayClick: () -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    onMonthStep: (YearMonth) -> Unit,
    onRangeClear: () -> Unit
) {
    when (filter.dateMode) {
        SearchDateMode.ALL -> Unit

        SearchDateMode.DAY -> {
            val day = filter.day ?: today
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选中日期", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(10.dp))
                FilterPill(dayLabel(day), selected = true, onClick = onDayClick)
            }
        }

        SearchDateMode.MONTH -> {
            val month = filter.month ?: YearMonth.now(bookkeepingZone())
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonthStep(month.minusMonths(1)) }) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "上一月",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${month.year}年${month.monthValue}月",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = { onMonthStep(month.plusMonths(1)) }) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = "下一月",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SearchDateMode.RANGE -> {
            val hasRange = filter.rangeStart != null || filter.rangeEnd != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("起止", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(10.dp))
                FilterPill(
                    filter.rangeStart?.let { dayLabel(it) } ?: "开始日期",
                    selected = filter.rangeStart != null,
                    onClick = onStartClick
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("~", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                FilterPill(
                    filter.rangeEnd?.let { dayLabel(it) } ?: "截止日期",
                    selected = filter.rangeEnd != null,
                    onClick = onEndClick
                )
                if (hasRange) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "清除",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onRangeClear)
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 汇总条 / 空态 / 结果卡片
// ---------------------------------------------------------------------------

/** 汇总条：「共 N 笔 · 支出 ¥x · 收入 ¥y」，12sp。金额展示统一走 Money.format（¥+千分位）。 */
@Composable
private fun SummaryBar(totalCount: Int, expenseTotal: Long, incomeTotal: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "共 $totalCount 笔",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = "·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "支出 ${Money.format(expenseTotal)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.tertiary
        )
        Text(text = "·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "收入 ${Money.format(incomeTotal)}",
            fontSize = 12.sp,
            color = IncomeGreen
        )
    }
}

/** 空态：无匹配账单 + 换条件的轻提示。 */
@Composable
private fun EmptyResult() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("无匹配账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "换个关键词或放宽筛选试试",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单日分组卡：日期 + 笔数为头，行间发丝分割线（右缩进 6dp，与首页账单卡同款）。 */
@Composable
private fun DayGroupCard(
    day: LocalDate,
    today: LocalDate,
    bills: List<Bill>,
    onEditBill: (Bill) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .rinkShadow(SearchCardShape)
            .clip(SearchCardShape)
            .then(applyCardGlass(SearchCardShape))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dayHeaderLabel(day, today),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${bills.size} 笔",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        bills.forEach { bill ->
            RinklDivider(endInset = 6.dp)
            SearchBillRow(bill, onEditBill)
        }
    }
}

/**
 * 单笔账单行：7dp 收支圆点 + 名称 16sp + 日期 14sp 次要字 + 金额 16sp Medium（支出 tertiary / 收入绿）。
 * 排版对齐月度明细 [com.example.rinklnote.ui.screen.bookkeeping.MonthDetailOverlay] 的账单行。
 */
@Composable
private fun SearchBillRow(bill: Bill, onEditBill: (Bill) -> Unit) {
    val isExpense = bill.billType == BillType.EXPENSE
    val localDate = Instant.ofEpochMilli(bill.date).atZone(bookkeepingZone()).toLocalDate()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEditBill(bill) }
            .padding(vertical = 8.dp),
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
                text = bill.remark ?: (bill.subCategoryName ?: bill.categoryName),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${localDate.monthValue}月${localDate.dayOfMonth}日",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = (if (isExpense) "-" else "+") + Money.format(bill.amountMinor),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
        )
    }
}

// ---------------------------------------------------------------------------
// 日期选择（material3 DatePickerDialog，转换逻辑对齐 BillEditOverlay：picker 一律走 UTC）
// ---------------------------------------------------------------------------

/** 单日期选择弹窗：确定/取消 + DatePicker（关闭年月切换折叠，观感更简）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.toPickerMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onConfirm(it.toPickerLocalDate()) }
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    ) {
        DatePicker(
            state = pickerState,
            showModeToggle = false
        )
    }
}

/** LocalDate → DatePicker 选值毫秒（UTC 当日 0 点，对齐 BillEditOverlay 的换算约定）。 */
private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** DatePicker 选值毫秒 → LocalDate（UTC 解回本地日期）。 */
private fun Long.toPickerLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

// ---------------------------------------------------------------------------
// 日期文案
// ---------------------------------------------------------------------------

/** 卡片头日期：本年「M月d日 周X」，跨年带年份「yyyy年M月d日 周X」。 */
private fun dayHeaderLabel(day: LocalDate, today: LocalDate): String {
    val base = "${day.monthValue}月${day.dayOfMonth}日 ${day.dayOfWeek.dayOfWeekLabel()}"
    return if (day.year == today.year) base else "${day.year}年$base"
}

/** 选择胶囊里的日期：带年份，避免跨年区间歧义。 */
private fun dayLabel(day: LocalDate): String =
    "${day.year}年${day.monthValue}月${day.dayOfMonth}日"

/** 周几中文短标签。 */
private fun DayOfWeek.dayOfWeekLabel(): String = when (value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}
