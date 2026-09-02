package com.example.rinklnote.ui.component


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import com.example.rinklnote.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.IncomeGreen

private enum class ChartType { LINE, BAR }

/** 趋势图标题：窗口天数不足10天时如实标注「本月前N日」，否则「最近10日」，避免硬说10日。 */
private fun chartWindowTitle(chartType: ChartType, dayCount: Int): String {
    val unit = if (chartType == ChartType.LINE) "走势" else "柱状"
    return if (dayCount < 10) "本月前${dayCount}日$unit" else "最近10日$unit"
}

@Composable
fun ChartBox(
    modifier: Modifier = Modifier,
    expenseData: List<Float>,
    totalExpense: Double,
    totalIncome: Double,
    labels: List<String>,
    currentMonth: Int = java.time.LocalDate.now().monthValue,
    dayCount: Int = 10,
    onDetailClick: () -> Unit = {}
) {
    var chartType by remember { mutableStateOf(ChartType.LINE) }
    val textMeasurer = rememberTextMeasurer()

    // Pre-capture colors for use in Canvas (DrawScope is not @Composable)
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    // Pre-measure the constant labels once (style + text are fixed for a given
    // dataset & theme). During the entry animation the per-frame draw loop only
    // *re-positions* these layouts — re-measuring them every frame was the main
    // ChartBox render cost (measure = text layout + shaping + font fallback).
    val amountLabelLayouts = remember(expenseData, tertiaryColor) {
        val style = TextStyle(fontSize = 8.sp, color = tertiaryColor, textAlign = TextAlign.Center)
        expenseData.map { textMeasurer.measure("%.2f".format(it), style) }
    }
    val axisLabelLayouts = remember(labels, onSurfaceVariantColor) {
        val style = TextStyle(fontSize = 9.sp, color = onSurfaceVariantColor, textAlign = TextAlign.Center)
        labels.map { textMeasurer.measure(it, style) }
    }

    val perIndexValue = remember(expenseData.size) { List(expenseData.size) { Animatable(0f) } }

    // 缩放上限用「目标数据」的 max（避免动画漂移），并只在数据变化时算一次（不再每帧重算）
    val maxVal = remember(expenseData) { expenseData.maxOrNull()?.coerceAtLeast(1f) ?: 1f }

    // Track previous data to detect which indices changed
    var prevExpenseData by remember { mutableStateOf(emptyList<Float>()) }

    LaunchedEffect(expenseData, chartType) {
        val isFirstRender = prevExpenseData.isEmpty()
        val isChartTypeChange = !isFirstRender && prevExpenseData == expenseData

        if (isFirstRender || isChartTypeChange) {
            // Full animation from zero — all indices
            perIndexValue.forEach { it.snapTo(0f) }
            coroutineScope {
                perIndexValue.forEachIndexed { i, anim ->
                    launch { anim.animateTo(expenseData[i], Motion.ChartDraw) }
                }
            }
        } else {
            // Animate only changed indices
            for (i in expenseData.indices) {
                val oldVal = prevExpenseData.getOrElse(i) { 0f }
                val newVal = expenseData[i]
                if (oldVal != newVal) {
                    perIndexValue[i].snapTo(oldVal)
                    launch { perIndexValue[i].animateTo(newVal, Motion.ChartUpdate) }
                }
            }
        }

        prevExpenseData = expenseData
    }

    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Title row: title + switch + detail buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 11.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chartWindowTitle(chartType, dayCount),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))

            // Switch button — toggle line/bar, canvas fills button
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { chartType = if (chartType == ChartType.LINE) ChartType.BAR else ChartType.LINE },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val pad = w * 0.22f
                    val innerW = w - pad * 2
                    val innerH = h - pad * 2
                    val barW = innerW / 7
                    val gap = (innerW - barW * 3) / 2
                    val heights = floatArrayOf(innerH * 0.5f, innerH * 0.9f, innerH * 0.65f)
                    val colors = listOf(onSurfaceVariantColor, primaryColor, onSurfaceVariantColor)
                    for (i in 0..2) {
                        val left = pad + i * (barW + gap)
                        drawRoundRect(
                            color = colors[i],
                            topLeft = Offset(left, pad + innerH - heights[i]),
                            size = Size(barW * 1.2f, heights[i]),
                            cornerRadius = CornerRadius(barW * 0.3f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Detail button — opens month detail overlay
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onDetailClick() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val pad = w * 0.22f
                    val innerW = w - pad * 2
                    val barH = h / 8
                    val gap = (h - pad * 2 - barH * 3) / 2
                    for (i in 0..2) {
                        val y = pad + i * (barH + gap)
                        val color = if (i == 1) primaryColor else onSurfaceVariantColor
                        val barWidth = if (i == 1) innerW else innerW * 0.75f
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(pad, y),
                            size = Size(barWidth, barH),
                            cornerRadius = CornerRadius(barH * 0.4f)
                        )
                    }
                }
            }
        }

        // Chart area — everything in one Canvas: chart + data labels + X-axis labels
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (expenseData.isEmpty()) return@Canvas

                val chartLeft = 0.dp.toPx()
                val chartRight = size.width
                val chartTop = 14.dp.toPx()      // room for amount labels
                val xAxisTop = size.height - 14.dp.toPx()  // room for X-axis labels
                val chartHeight = xAxisTop - chartTop

                val step = (chartRight - chartLeft) / (expenseData.size - 1).coerceAtLeast(1)

                when (chartType) {
                    ChartType.LINE -> {
                        // Draw line path using per-index animated values
                        val path = Path()
                        expenseData.indices.forEach { index ->
                            val dispVal = perIndexValue[index].value
                            val x = chartLeft + step * index
                            val y = xAxisTop - chartHeight * (dispVal / maxVal)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = tertiaryColor,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Dots + amount labels (labels use final values, not animated)
                        expenseData.indices.forEach { index ->
                            val dispVal = perIndexValue[index].value
                            val x = chartLeft + step * index
                            val y = xAxisTop - chartHeight * (dispVal / maxVal)

                            drawCircle(
                                color = tertiaryColor,
                                radius = 3.dp.toPx(),
                                center = Offset(x, y)
                            )

                            val label = amountLabelLayouts[index]
                            drawText(
                                textLayoutResult = label,
                                topLeft = Offset(
                                    x - label.size.width / 2f,
                                    y - label.size.height - 4.dp.toPx()
                                )
                            )
                        }
                    }

                    ChartType.BAR -> {
                        val barWidth = 10.dp.toPx()
                        expenseData.indices.forEach { index ->
                            val dispVal = perIndexValue[index].value
                            val centerX = chartLeft + step * index
                            val barHeight = chartHeight * (dispVal / maxVal)
                            val x = centerX - barWidth / 2
                            val y = xAxisTop - barHeight

                            drawRoundRect(
                                color = tertiaryColor,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )

                            val label = amountLabelLayouts[index]
                            drawText(
                                textLayoutResult = label,
                                topLeft = Offset(
                                    centerX - label.size.width / 2f,
                                    y - label.size.height - 4.dp.toPx()
                                )
                            )
                        }
                    }
                }

                // Draw X-axis date labels — same x positions as data points
                if (labels.isNotEmpty()) {
                    labels.forEachIndexed { index, label ->
                        val x = chartLeft + step * index
                        val measured = axisLabelLayouts[index]
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                x - measured.size.width / 2f,
                                xAxisTop + 2.dp.toPx()
                            )
                        )
                    }
                }
            }
        }

        // Summary bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(bottomStart = 15.dp, bottomEnd = 15.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "${currentMonth}月：",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "支出${String.format("%.2f", totalExpense)}  ",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "收入${String.format("%.2f", totalIncome)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = IncomeGreen
            )
        }
    }
}
