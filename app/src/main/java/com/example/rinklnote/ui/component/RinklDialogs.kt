package com.example.rinklnote.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rinklnote.ui.theme.LocalRinklColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 对话框统一形状：20dp 圆角（比 15dp 卡片链略收出一档模态层级感）。 */
private val RinklDialogShape = RoundedCornerShape(20.dp)

/**
 * M3 组件的应用皮作用域：把 colorScheme 的主色系映射到 PRIMARY 槽令牌，
 * 让仍在用的 M3 组件（DatePicker 等）继承应用品牌色，而不是谷歌默认蓝。
 * 需要包 M3 组件的场景用 [RinklDatePickerDialog]；新对话框一律走本文件的 [AlertDialog]。
 */
@Composable
fun RinklDialogTheme(content: @Composable () -> Unit) {
    val rinkl = LocalRinklColors.current
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = rinkl.themeColor,
            onPrimary = Color.White,
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
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
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
                        TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
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
 * 共享单日期选择弹窗：主题化 DatePicker（PRIMARY 槽驱动选中态与按钮）
 * + 确定/取消；搜索页与账单编辑页共用同一实现。
 * 选值换算沿用全 App 约定：picker 一律走 UTC 当日 0 点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RinklDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    RinklDialogTheme {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            shape = RinklDialogShape,
            colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            confirmButton = {
                TextButton(
                    onClick = { state.selectedDateMillis?.let { onConfirm(it.toPickerLocalDate()) } }
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
            // 隐藏 M3 默认英文「Select date」头部：日期信息由 headline（随系统语言）承载
            DatePicker(
                state = state,
                showModeToggle = false,
                title = { }
            )
        }
    }
}

/** DatePicker 选值毫秒 → LocalDate（UTC 解回本地日期）。 */
private fun Long.toPickerLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
