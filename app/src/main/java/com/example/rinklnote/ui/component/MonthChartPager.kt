package com.example.rinklnote.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.domain.PieSlice
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 饼图配色：主蓝起头，末位灰色固定给「其他」。 */
val PiePalette = listOf(
    Color(0xFF7EC1FC),
    Color(0xFFF97D1D),
    Color(0xFF04A433),
    Color(0xFF9B59B6),
    Color(0xFFF2B134),
    Color(0xFFCA3032),
    Color(0xFFB0B0B0)
)

private enum class MonthChartTab(val label: String) { PIE("占比"), LINE("折线"), BAR("柱状") }

/**
 * 月度明细页可滑图表：三段式标签(占比|折线|柱状) + HorizontalPager 3 页。
 * 页1 支出分类占比饼图；页2 30日折线图；页3 30日柱状图。整月为窗口（无账日=0），
 * 今天用主色高亮（折线/柱状）。饼图可点分类筛选，折线/柱状仅视觉高亮不筛选。
 * 数据由 ViewModel 聚合后传入（pieSlices/daySeries/maxSeries），本组件只负责渲染。
 */
@Composable
fun MonthChartPager(
    month: LocalDate,
    pieSlices: List<PieSlice>,
    daySeries: List<Float>,
    maxSeries: Float,
    selectedCategory: String?,
    onCategoryTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { MonthChartTab.entries.size })
    val tabs = MonthChartTab.entries
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
//            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.Transparent)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) { page ->
            when (page) {
                0 -> PiePage(slices = pieSlices, selectedCategory = selectedCategory, onCategoryTap = onCategoryTap)
                1 -> TrendPage(month = month, daySeries = daySeries, maxSeries = maxSeries, chartType = MonthChartTab.LINE)
                2 -> TrendPage(month = month, daySeries = daySeries, maxSeries = maxSeries, chartType = MonthChartTab.BAR)
            }
        }

        // 底部浅提示：当前页小圆点，可点跳转；左右滑动切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.indices.forEach { index ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (active) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }
}

/** 页1：支出分类占比——左侧方形环图 + 右侧紧凑图例行；图例可点筛选。 */
@Composable
private fun PiePage(
    slices: List<PieSlice>,
    selectedCategory: String?,
    onCategoryTap: (String) -> Unit
) {
    if (slices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("本月暂无支出", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 9.sp)
    val layouts = slices.map {
        if (it.pct >= 0.06f) textMeasurer.measure(AnnotatedString("${(it.pct * 100).roundToInt()}%"), labelStyle) else null
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左：方形环图（占约 44% 宽）
        Box(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxHeight().aspectRatio(1f)) {
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
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右：紧凑图例（色点·名称·金额·%），可点筛选
        Column(
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            slices.forEachIndexed { index, slice ->
                val isSelected = selectedCategory == slice.name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable { onCategoryTap(slice.name) }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(PiePalette[index % PiePalette.size])
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        slice.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${Money.format(slice.amount)}·${(slice.pct * 100).roundToInt()}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 页2/页3：整月每日支出折线图 / 柱状图。无账日=0，今天主色高亮。 */
@Composable
private fun TrendPage(
    month: LocalDate,
    daySeries: List<Float>,
    maxSeries: Float,
    chartType: MonthChartTab
) {
    val daysInMonth = month.lengthOfMonth()
    val values = daySeries
    val maxVal = maxSeries
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 今天的 day-of-month；仅当展示的是当月，且今天在月内才高亮
    val todayInMonth = remember(month) {
        val today = LocalDate.now(bookkeepingZone())
        if (today.year == month.year && today.monthValue == month.monthValue) today.dayOfMonth else -1
    }

    val textMeasurer = rememberTextMeasurer()
    // X 轴标签：每 7 天一个（1,8,15,22,29），避免 30 个标签挤压
    val labelLayouts = remember(daysInMonth, onSurfaceVariant) {
        val style = TextStyle(fontSize = 9.sp, color = onSurfaceVariant, textAlign = TextAlign.Center)
        (1..daysInMonth).map { day -> textMeasurer.measure("$day", style) }
    }
    val todayLabelLayout = remember(todayInMonth, daySeries) {
        if (todayInMonth > 0) textMeasurer.measure(
            AnnotatedString(Money.format(daySeries.getOrElse(todayInMonth - 1) { 0f }.toLong())),
            TextStyle(fontSize = 8.sp, color = primary, textAlign = TextAlign.Center)
        ) else null
    }

    // 入场动画：单进度 0→1，重新进入本页时重放
    val progress = remember { Animatable(0f) }
    LaunchedEffect(chartType) { progress.animateTo(1f, tween(600)) }

    Canvas(modifier = Modifier.fillMaxWidth().fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
        val n = values.size
        if (n < 2) return@Canvas
        val chartLeft = 0.dp.toPx()
        val chartRight = size.width
        val chartTop = 16.dp.toPx()
        val xAxisTop = size.height - 16.dp.toPx()
        val chartHeight = xAxisTop - chartTop
        val step = (chartRight - chartLeft) / (n - 1)
        val disp = { i: Int -> values[i] * progress.value }

        when (chartType) {
            MonthChartTab.LINE -> {
                val path = Path()
                values.indices.forEach { i ->
                    val x = chartLeft + step * i
                    val y = xAxisTop - chartHeight * (disp(i) / maxVal)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = tertiary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

                values.indices.forEach { i ->
                    val x = chartLeft + step * i
                    val y = xAxisTop - chartHeight * (disp(i) / maxVal)
                    val isToday = (i + 1) == todayInMonth
                    if (isToday) {
                        // 今天：垂直淡色引导线 + 主色实心点 + 金额标签
                        drawLine(
                            color = primary.copy(alpha = 0.3f),
                            start = Offset(x, chartTop),
                            end = Offset(x, xAxisTop),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawCircle(color = primary, radius = 4.5.dp.toPx(), center = Offset(x, y))
                        todayLabelLayout?.let { layout ->
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    x - layout.size.width / 2f,
                                    y - layout.size.height - 6.dp.toPx()
                                )
                            )
                        }
                    } else {
                        drawCircle(color = tertiary, radius = 3.dp.toPx(), center = Offset(x, y))
                    }
                }
            }

            MonthChartTab.BAR -> {
                val barWidth = (chartRight - chartLeft) / n * 0.5f
                values.indices.forEach { i ->
                    val centerX = chartLeft + step * i
                    val barHeight = chartHeight * (disp(i) / maxVal)
                    val x = centerX - barWidth / 2
                    val y = xAxisTop - barHeight
                    val isToday = (i + 1) == todayInMonth
                    drawRoundRect(
                        color = if (isToday) primary else tertiary,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    )
                    if (isToday) {
                        todayLabelLayout?.let { layout ->
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    centerX - layout.size.width / 2f,
                                    y - layout.size.height - 6.dp.toPx()
                                )
                            )
                        }
                    }
                }
            }

            MonthChartTab.PIE -> Unit
        }

        // X 轴日期标签：每 7 天一个
        values.indices.forEach { i ->
            val day = i + 1
            if ((day - 1) % 7 == 0) {
                val x = chartLeft + step * i
                val layout = labelLayouts[i]
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x - layout.size.width / 2f, xAxisTop + 2.dp.toPx())
                )
            }
        }
    }
}
