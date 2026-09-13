package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.HeatmapBox
import com.example.rinklnote.ui.component.MonthChartPager
import com.example.rinklnote.ui.component.MonthHeatmap
import com.example.rinklnote.ui.component.RinklCardFrostedStyle
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import java.time.Instant
import java.time.LocalDate

/** 月度明细筛选状态：无 | 只看某天 | 只看某分类。热力图/图表始终显示整月，仅下方明细跟随收窄。 */
private sealed interface MonthFilter {
    data object None : MonthFilter
    data class Day(val day: Int) : MonthFilter
    data class Category(val name: String) : MonthFilter
}

/** 本页卡片统一形状：与首页账单卡/合计条/热力图同款 15dp。 */
private val DetailCardShape = RoundedCornerShape(15.dp)

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
    onEditBill: (Bill) -> Unit,
    onBack: () -> Unit
) {
    // 选中筛选；切换可见/换月时自动重置为「无」
    var filter by remember(bills) { mutableStateOf<MonthFilter>(MonthFilter.None) }

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
            .map { (name, bl) -> Triple(name, bl.sumOf { it.amountMinor }, bl) }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 背景层：无自选照片时自铺纯白（并注册毛玻璃采样源）；有照片时不铺底，透出 nav 层 AppBackground。
        if (backgroundUri == null) {
            val bg = hazeState
            if (bg != null) {
                DefaultHazeBackground(bg)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                // 顶部垫高 = 浮动顶栏胶囊（上边距 8dp + 高 46dp），与首页「底栏之上有留白」同理
                top = 54.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
            item(key = "banner") { FramedBanner(expenseTotal, incomeTotal) }

            // 图表（占比|折线|柱状 三段可滑）
            item(key = "chart-pager") {
                Spacer(modifier = Modifier.height(10.dp))
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

            // 每日支出热力图（复用首页组件）→ 点天筛选
            item(key = "heatmap") {
                Spacer(modifier = Modifier.height(10.dp))
                HeatmapBox(
                    heatmap = MonthHeatmap(
                        year = month.year,
                        monthValue = month.monthValue,
                        firstWeekday = month.dayOfWeek.value,
                        daysInMonth = month.lengthOfMonth(),
                        dailyExpense = monthDetail.dayAmounts.mapValues { it.value.toFloat() }
                    ),
                    initiallyExpanded = true,
                    selectedDay = (filter as? MonthFilter.Day)?.day,
                    onDayTap = { day ->
                        filter = if (filter == MonthFilter.Day(day)) MonthFilter.None else MonthFilter.Day(day)
                    }
                )
            }

            if (filter != MonthFilter.None) {
                item(key = "filter") {
                    Spacer(modifier = Modifier.height(10.dp))
                    FilterBanner(filter) { filter = MonthFilter.None }
                }
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
                    Spacer(modifier = Modifier.height(10.dp))
                    CategoryCard(catName, subtotal, catBills, onEditBill)
                }
            }
        }

        MonthDetailTopBar(
            monthLabel = monthLabel,
            hasPhoto = backgroundUri != null,
            hazeState = hazeState,
            onBack = onBack
        )
    }
}

/** 顶部画框头图：树景装饰图装进统一卡片链（1dp 画框边 + 15dp 圆角 + 轻阴影），底部渐变 scrim 上压支出/收入合计。 */
@Composable
private fun FramedBanner(
    expenseTotal: Long,
    incomeTotal: Long,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(DetailCardShape)
            .clip(DetailCardShape)
            .then(applyCardGlass(DetailCardShape))
            .height(170.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.tree),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        // 压图合计的渐变 scrim：越靠近底部越深，保证白字可读。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f)
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("支出", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = Money.format(expenseTotal),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("收入", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = Money.format(incomeTotal),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

/** 浮动顶栏胶囊：与底部导航同款双材质——自选照片时挂 RinklCardFrostedStyle 毛玻璃，否则实底 surface。 */
@Composable
private fun MonthDetailTopBar(
    monthLabel: String,
    hasPhoto: Boolean,
    hazeState: HazeState?,
    onBack: () -> Unit
) {
    val capsuleShape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .rinkShadow(capsuleShape)
                .clip(capsuleShape)
                .then(
                    if (hasPhoto && hazeState != null) {
                        Modifier.hazeEffect(hazeState, RinklCardFrostedStyle)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    }
                )
                .then(applyCardGlass(capsuleShape))
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = monthLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 右侧等宽占位，让标题在胶囊内真正居中
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(40.dp))
        }
    }
}

/** 分类分组卡：分类名 + 小计为头，行间发丝分割线（右缩进 6dp，与首页账单卡同款）。 */
@Composable
private fun CategoryCard(
    categoryName: String,
    subtotal: Long,
    bills: List<Bill>,
    onEditBill: (Bill) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(DetailCardShape)
            .clip(DetailCardShape)
            .then(applyCardGlass(DetailCardShape))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(categoryName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = Money.format(subtotal),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        bills.forEach { bill ->
            RinklDivider(endInset = 6.dp)
            DetailRow(bill, onEditBill)
        }
    }
}

/** 顶部筛选横幅：小胶囊显示「已筛选：第X天 / 分类名」+ 可点「清除」。 */
@Composable
private fun FilterBanner(filter: MonthFilter, onClear: () -> Unit) {
    val label = when (filter) {
        MonthFilter.None -> ""
        is MonthFilter.Day -> "第${filter.day}天"
        is MonthFilter.Category -> filter.name
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("已筛选：$label", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "清除",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onClear() }
                .padding(horizontal = 4.dp, vertical = 10.dp)
        )
    }
}

/** 单笔账单行：点击进入账单编辑；排版对齐首页账单行（16sp 名称/金额、13sp 日期、7dp 收支圆点）。 */
@Composable
private fun DetailRow(bill: Bill, onEditBill: (Bill) -> Unit) {
    val isExpense = bill.billType == BillType.EXPENSE
    val localDate = Instant.ofEpochMilli(bill.date).atZone(bookkeepingZone()).toLocalDate()
    val dateLabel = "${localDate.monthValue}月${localDate.dayOfMonth}日"
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
                text = dateLabel,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = bill.remark ?: (bill.subCategoryName ?: bill.categoryName),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
