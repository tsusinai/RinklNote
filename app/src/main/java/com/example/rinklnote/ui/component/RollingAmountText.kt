package com.example.rinklnote.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.rinklnote.util.Money

/**
 * 翻牌式滚动金额组件（2026-10-03 V2 数字微动效）：
 * 金额数字变动时沿纵向平滑滚入滚出过渡，初次进入或静止时不闪烁；
 * 禁止折行（softWrap = false，继承 commit a28b011 模拟器 1.5x 防截断约束）。
 */
@Composable
fun RollingAmountText(
    amountMinor: Long,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    trailingSpace: String = ""
) {
    val formatted = Money.formatPlain(amountMinor) + trailingSpace
    AnimatedContent(
        targetState = amountMinor to formatted,
        modifier = modifier,
        transitionSpec = {
            if (targetState.first > initialState.first) {
                // 金额增加：由下向上滑入
                (slideInVertically { height -> height / 2 } + fadeIn()) togetherWith
                        (slideOutVertically { height -> -height / 2 } + fadeOut())
            } else if (targetState.first < initialState.first) {
                // 金额减少：由上向下滑入
                (slideInVertically { height -> -height / 2 } + fadeIn()) togetherWith
                        (slideOutVertically { height -> height / 2 } + fadeOut())
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "RollingAmount"
    ) { (_, text) ->
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}
