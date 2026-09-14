package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局悬浮顶栏的内容高度。
 *
 * 首页四个 tab 与月度详情共用该值，避免标题行、图标行各自测量后出现高度漂移。
 */
val RinklTopBarContentHeight = 46.dp

/** 状态栏以下的顶栏总高度，供列表首项预留等量空间。 */
@Composable
fun rememberRinklTopBarHeight(): Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.statusBars.getTop(density).toDp() } + RinklTopBarContentHeight
}

/**
 * 无边框悬浮顶栏容器：内容固定在统一高度内，背景内容滚动到顶栏下方时由 [scrimAlpha] 渐显暗底。
 */
@Composable
fun RinklTopBar(
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.30f * scrimAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(RinklTopBarContentHeight)
                .padding(horizontal = horizontalPadding),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}
