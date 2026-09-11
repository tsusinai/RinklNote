package com.example.rinklnote.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.rinklnote.ui.theme.LocalRinklColors

/**
 * 全 App 统一分割线——**与「每日账单」卡片里那根线完全同款**：1 物理像素发丝线（`strokeWidth = 1f`），
 * 颜色取主题令牌 [com.example.rinklnote.ui.theme.RinklColors.dividerColor]
 * （默认 = 每日账单灰 #A5A5A5；用户在「自定义主题」里改了边框色则跟随）。
 *
 * 为什么不用 `HorizontalDivider`：它的厚度是 1dp（≈2.6px @xxhdpi），高分屏上明显偏粗；
 * 且默认色来自 `surfaceVariant`（#EFF1F3），与账单页的 #A5A5A5 灰线不是一回事——
 * 这正是此前「各页面框线/分割线颜色不统一」的来源之一。
 *
 * 高度固定 1dp（走 `Modifier.height`），因此**调用方不要再传高度**，只传 `endInset` / 外边距。
 *
 * @param endInset 右侧留白（每日账单卡片里是 6dp；设置列表一般 0dp）
 */
@Composable
fun RinklDivider(
    modifier: Modifier = Modifier,
    endInset: Dp = 0.dp,
    color: Color = LocalRinklColors.current.dividerColor
) {
    val insetPx = with(LocalDensity.current) { endInset.toPx() }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset((size.width - insetPx).coerceAtLeast(0f), size.height / 2f),
            strokeWidth = 1f
        )
    }
}
