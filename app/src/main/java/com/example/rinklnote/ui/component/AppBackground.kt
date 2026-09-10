package com.example.rinklnote.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 应用级背景：用户自选的图库照片铺满整个窗口（含状态栏/导航栏/底部 tab 栏之下），
 * 同时作为全局毛玻璃采样源，供各页面卡片与底栏 hazeEffect 采样。
 * 未设置照片时不绘制，露出 AppNavigation 根层的主题背景色。
 */
@Composable
fun AppBackground(backgroundUri: String?, hazeState: HazeState, modifier: Modifier = Modifier) {
    if (backgroundUri == null) return
    AsyncImage(
        model = backgroundUri,
        contentDescription = null,
        modifier = modifier
            .fillMaxSize()
            .hazeSource(hazeState),
        contentScale = ContentScale.Crop
    )
}
