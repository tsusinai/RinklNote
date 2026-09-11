package com.example.rinklnote.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.Blue40
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.ui.component.applyCardGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import java.time.LocalDate

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

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun HeatmapBox(
    modifier: Modifier = Modifier,
    heatmap: MonthHeatmap,
    hazeState: HazeState,
    backgroundUri: String? = null,
    onDetailClick: () -> Unit = {}
) {
    val hotColor = Blue40
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

    // 展开/收起：默认收回，点击标题区切换。
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "heatmap-chevron"
    )

    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(hazeState, backgroundUri, RoundedCornerShape(15.dp)))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // 标题行：左侧标题+chevron 可点（切换展开/收起），右侧「明细」独立打开汇总。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "本月支出热力图 ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
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
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "明细",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onDetailClick() }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(durationMillis = 220)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 220))
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
                            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(top = 2.dp))

                // 日期格子
                val leadingBlanks = heatmap.firstWeekday - 1
                val totalCells = leadingBlanks + heatmap.daysInMonth
                val rows = (totalCells + 6) / 7
                val cellShape = RoundedCornerShape(6.dp)

                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - leadingBlanks + 1
                            if (day in 1..heatmap.daysInMonth) {
                                val amount = heatmap.dailyExpense[day] ?: 0f
                                val intensity = (amount / maxExpense).coerceIn(0f, 1f)
                                val bg = lerp(EmptyBlue, hotColor, intensity)
                                val textColor =
                                    if (intensity > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(CELL_ASPECT_RATIO)
                                        .padding(2.dp)
                                        .then(
                                            if (day == todayDay) Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary,
                                                cellShape
                                            ) else Modifier
                                        )
                                        .clip(cellShape)
                                        .background(bg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = day.toString(),
                                        fontSize = 10.sp,
                                        color = textColor,
                                        fontWeight = if (day == todayDay) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            } else {
                                Box(modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(CELL_ASPECT_RATIO))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(top = 4.dp))

                // 图例
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "支出少",
                        fontSize = 10.sp,
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
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
