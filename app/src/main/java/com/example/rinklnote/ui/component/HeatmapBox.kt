package com.example.rinklnote.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.example.rinklnote.ui.component.motionClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.ui.component.applyCardGlass
import java.time.LocalDate
import kotlin.math.roundToLong

/** 月度每日支出热力图：周一起始的月历格子，每个格子按当日支出深浅着色。
 *  当日支出越高颜色越深，today 加主色描边；无支出日为最浅底色。可点击标题区收回/展开。 */
data class MonthHeatmap(
    val year: Int,
    val monthValue: Int,
    val firstWeekday: Int,   // 1=周一..7=周日 (ISO)
    val daysInMonth: Int,
    val dailyExpense: Map<Int, Float>
)

private val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val EmptyBlue = Color(0xFFEAF0F8)

/** 格子宽/高比：>1 即比正方形矮一截，用于降低热力图整体高度。 */
private const val CELL_ASPECT_RATIO = 1.7f

/** 把整月切成三段（1-10 / 11-20 / 21-月末），各段支出合计，用于收起态的三格微型热力图。 */
private fun heatRangeTotals(dailyExpense: Map<Int, Float>, daysInMonth: Int): List<Float> {
    val ranges = listOf(1..10, 11..20, 21..daysInMonth)
    return ranges.map { r -> r.fold(0f) { acc, d -> acc + (dailyExpense[d] ?: 0f) } }
}

/**
 * 月度每日支出热力图（首页与月度详情共用）。
 *
 * @param onDetailClick 「明细」入口回调；传 null 隐藏入口（月度详情页本身就在明细里，无需再跳）
 * @param initiallyExpanded 初始是否展开月历（首页默认收起；详情页作为选天工具默认展开）
 * @param selectedDay 当前选中的日期（1 起始）；非空时格子加粗描边
 * @param onDayTap 点选某天的回调；传 null 则格子不可点（首页只读）
 * @param compact 月度详情使用的无卡片紧凑布局；其它调用点保持原样
 */
@Composable
fun HeatmapBox(
    heatmap: MonthHeatmap,
    modifier: Modifier = Modifier,
    onDetailClick: (() -> Unit)? = null,
    initiallyExpanded: Boolean = false,
    selectedDay: Int? = null,
    onDayTap: ((Int) -> Unit)? = null,
    compact: Boolean = false
) {
    // 热力深色端走「热力图颜色」槽（自定义主题 HEATMAP，默认 Blue40）；
    // 浅色端固定 EmptyBlue，强度 0..1 在两端间自动 lerp 出深浅，未自定义时观感不变。
    val hotColor = LocalRinklColors.current.heatmapColor
    // 缓存重计算：maxExpense / rangeTotals / rangeMax / today 只随 heatmap 变化，重组时不重复算。
    val maxExpense = remember(heatmap) {
        heatmap.dailyExpense.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    }
    val today = remember { LocalDate.now(bookkeepingZone()) }
    val todayDay = remember(heatmap, today) {
        if (heatmap.year == today.year && heatmap.monthValue == today.monthValue) today.dayOfMonth else -1
    }

    // 收起态三段（1-10/11-20/21-月末）的支出合计及其相对强度。
    val rangeTotals = remember(heatmap) { heatRangeTotals(heatmap.dailyExpense, heatmap.daysInMonth) }
    val rangeMax = remember(rangeTotals) { rangeTotals.maxOrNull()?.coerceAtLeast(1f) ?: 1f }

    // 展开/收起：点击标题区切换。
    var expanded by remember(heatmap.year, heatmap.monthValue) { mutableStateOf(initiallyExpanded) }
    // 紧凑模式明确指定随字体缩放的高度，避免宽高比与最小高度冲突而裁切数字。
    val cellSize = if (compact) Modifier.height((26f * LocalDensity.current.fontScale).dp)
        else Modifier.aspectRatio(CELL_ASPECT_RATIO)
    val interactionSource = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.Fade,
        label = "heatmap-chevron"
    )

    Column(
        modifier = modifier
            .then(if (compact) Modifier else Modifier
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(15.dp))
                .then(applyCardGlass(RoundedCornerShape(15.dp)))
                .padding(horizontal = 12.dp, vertical = 12.dp))
    ) {
        // 标题行：左侧标题+chevron 可点（切换展开/收起），右侧「明细」独立打开汇总。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) 32.dp else 0.dp)
                    .motionClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        // 读屏动作名随状态切换（默认只有「双击激活」）。
                        onClickLabel = if (expanded) "收起月历" else "展开月历"
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "本月支出热力图",
                    fontSize = if (compact) 16.sp else 12.sp,
                    fontWeight = if (compact) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!expanded) {
                    rangeTotals.forEach { rangeTotal ->
                        val rangeIntensity = (rangeTotal / rangeMax).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .height(12.dp)
                                .width(27.dp)
                                .padding(horizontal = 3.dp)
                                .background(lerp(EmptyBlue, hotColor, rangeIntensity),RoundedCornerShape(2.dp))
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }



                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "▾",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
            onDetailClick?.let { detail ->
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "明细",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.motionClickable { detail() }
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = Motion.Expand),
            exit = shrinkVertically(animationSpec = Motion.Expand)
        ) {
            Column {
                Spacer(modifier = Modifier.padding(top = 4.dp))

                // 星期表头（周一起）
                Row(modifier = Modifier.fillMaxWidth()) {
                    weekdayLabels.forEach {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(top = 2.dp))

                // 日期格子
                val leadingBlanks = heatmap.firstWeekday - 1
                val totalCells = leadingBlanks + heatmap.daysInMonth
                val rows = (totalCells + 6) / 7
                val cellShape = RoundedCornerShape(6.dp)

                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val emptyCellColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else EmptyBlue

                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - leadingBlanks + 1
                            if (day in 1..heatmap.daysInMonth) {
                                val amount = heatmap.dailyExpense[day] ?: 0f
                                val intensity = (amount / maxExpense).coerceIn(0f, 1f)
                                val bg = lerp(emptyCellColor, hotColor, intensity)
                                val textColor =
                                    if (intensity > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface
                                val dayTap = onDayTap
                                val isSelected = dayTap != null && selectedDay == day
                                // 读屏语义：dailyExpense 存的是「分」（Float），直接走 Money.format 出「¥xx」；
                                // 选中态用 stateDescription 单独标注，不拼进描述串。
                                val daySemantic =
                                    "${heatmap.monthValue}月${day}日，" +
                                        if (amount > 0f) "支出 ${Money.format(amount.roundToLong())}" else "无支出"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(cellSize)
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = daySemantic
                                            if (isSelected) stateDescription = "已选中"
                                        }
                                        .padding(2.dp)
                                        .then(
                                            when {
                                                isSelected -> Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    cellShape
                                                )
                                                !compact && day == todayDay -> Modifier.border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    cellShape
                                                )
                                                else -> Modifier
                                            }
                                        )
                                        .clip(cellShape)
                                        .background(bg)
                                        .then(
                                            if (dayTap != null) {
                                                // 可点格子：Role.Button + 明确动作名「查看当天账单」。
                                                Modifier.motionClickable(
                                                    onClickLabel = if (isSelected) "显示整月账单" else "查看当天账单",
                                                    role = Role.Button
                                                ) { dayTap(day) }
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = day.toString(),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = textColor,
                                        fontWeight = if (day == todayDay) FontWeight.Bold else FontWeight.Normal,
                                        // 日期数字已并入格子 contentDescription，单独读一遍是冗余。
                                        modifier = Modifier
                                            .offset(y = if (compact && day == todayDay) (-1).dp else 0.dp)
                                            .clearAndSetSemantics { }
                                    )
                                    if (compact && day == todayDay) {
                                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                                            .size(3.dp).clip(RoundedCornerShape(2.dp))
                                            .background(MaterialTheme.colorScheme.primary))
                                    }
                                }
                            } else {
                                Box(modifier = Modifier
                                    .weight(1f)
                                    .then(cellSize))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(top = 4.dp))

                // 图例
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "支出少",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.padding(start = 4.dp))
                    Row {
                        listOf(0f, 1 / 3f, 2 / 3f, 1f).forEach { t ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 1.dp)
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(lerp(EmptyBlue, hotColor, t))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.padding(start = 4.dp))
                    Text(
                        text = "支出多",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
