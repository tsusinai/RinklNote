package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.component.motionClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.rinklnote.ui.component.RinklTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.component.RinklDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.bookkeepingZone
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 对话框统一形状：20dp 圆角（比 15dp 卡片链略收出一档模态层级感）。 */
private val RinklDialogShape = RoundedCornerShape(20.dp)

/** 日期弹窗大标题：如「2026年10月10日 周六」。 */
private val pickerHeadlineFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEE", Locale.CHINESE)

/** 日期弹窗月份导航标签：如「2026年10月」。 */
private val pickerMonthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINESE)

/** 日历周起始统一周一，表头与网格偏移共用该约定。 */
private val pickerWeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * M3 组件的应用皮作用域：把 colorScheme 的主色系映射到 PRIMARY 槽令牌，
 * 让仍在用的 M3 组件继承应用品牌色，而不是谷歌默认蓝。
 * 需要包 M3 组件的场景用 [RinklDialogTheme]；新对话框一律走本文件的 [AlertDialog]。
 */
@Composable
fun RinklDialogTheme(content: @Composable () -> Unit) {
    val rinkl = LocalRinklColors.current
    val onPrimaryColor = if (rinkl.themeColor.luminance() > 0.6f) Color.Black else Color.White
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = rinkl.themeColor,
            onPrimary = onPrimaryColor,
            primaryContainer = rinkl.themeColor.copy(alpha = 0.16f),
            onPrimaryContainer = rinkl.themeColor
        ),
        content = content
    )
}

/**
 * 应用皮 AlertDialog：与 androidx.compose.material3.AlertDialog **同签名**的换装版。
 * 视觉 = 20dp 圆角纯色面板 + 17sp Medium 标题 + 14sp/20sp 正文 + 右下动作行；
 * 各调用点只需把 import 从 material3 换到本包即可整体换皮，参数与调用形态不变。
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RinklDialogShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    properties: DialogProperties = DialogProperties()
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            shape = shape,
            color = containerColor,
            tonalElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                icon?.let {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { it() }
                }
                title?.let {
                    ProvideTextStyle(
                        TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    ) {
                        Box(
                            modifier = Modifier.padding(
                                bottom = if (text != null) 10.dp else 0.dp
                            )
                        ) { it() }
                    }
                }
                text?.let {
                    ProvideTextStyle(TextStyle(fontSize = 14.sp, lineHeight = 20.sp)) { it() }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    Spacer(modifier = Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

/**
 * 共享单日期选择弹窗：自绘中文日历（周一起始），与应用卡片同风格——
 * 顶部选中日期大标题、月份导航、7 列日期网格（选中 = PRIMARY 槽实心圆，
 * 今天 = 主题色文字）、右下取消/确定。搜索页与账单编辑页共用同一实现。
 * 入参出参均为业务 LocalDate（Asia/Shanghai 语义由调用方保证），不再经 UTC 毫秒换算。
 */
@Composable
fun RinklDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDate by remember { mutableStateOf(initialDate) }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(initialDate)) }
    RinklDialogTheme {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RinklDialogShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(
                        text = selectedDate.format(pickerHeadlineFormatter),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayedMonth.format(pickerMonthFormatter),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PickerNavButton(label = "‹", onClick = { displayedMonth = displayedMonth.minusMonths(1) })
                            PickerNavButton(label = "›", onClick = { displayedMonth = displayedMonth.plusMonths(1) })
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        pickerWeekdayLabels.forEach { label ->
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    MotionContent(displayedMonth) { month ->
                        // 周一起始的 7 列网格：首列偏移 = (周一为 1 的 dayOfWeek - 1)，
                        // 尾部空位用等宽 Spacer 占位，保证各列对齐
                        val leadingBlank = month.atDay(1).dayOfWeek.value - 1
                        val daysInMonth = month.lengthOfMonth()
                        val rowCount = (leadingBlank + daysInMonth + 6) / 7
                        val today = LocalDate.now(bookkeepingZone())
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(rowCount) { row ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    repeat(7) { col ->
                                        val dayNumber = row * 7 + col - leadingBlank + 1
                                        if (dayNumber in 1..daysInMonth) {
                                            val date = month.atDay(dayNumber)
                                            val isSelected = date == selectedDate
                                            val isToday = date == today
                                            val selectionColor by animateColorAsState(
                                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                Motion.SelectColor, label = "日期选中底色"
                                            )
                                            Box(
                                                modifier = Modifier.weight(1f),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(CircleShape)
                                                        .background(selectionColor)
                                                        .motionClickable { selectedDate = date },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = dayNumber.toString(),
                                                        fontSize = 14.sp,
                                                        fontWeight = if (isSelected || isToday) {
                                                            FontWeight.Medium
                                                        } else {
                                                            FontWeight.Normal
                                                        },
                                                        color = when {
                                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                                            isToday -> MaterialTheme.colorScheme.primary
                                                            else -> MaterialTheme.colorScheme.onSurface
                                                        }
                                                    )
                                                }
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "取消",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { onConfirm(selectedDate) }) {
                            Text(
                                text = "确定",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 月份导航小圆钮：32dp 圆形点击区，字符箭头不引入图标依赖。 */
@Composable
private fun PickerNavButton(
    label: String,
    onClick: () -> Unit
) {
    val desc = if (label == "‹") "上个月" else "下个月"
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = desc }
            .motionClickable(onClickLabel = desc, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
