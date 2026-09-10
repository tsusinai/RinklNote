package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 记账/资产/预算页默认背景：柔和的主色→背景渐变，作为毛玻璃（haze）的 blur 源。
 *
 * - 用户未自选照片时由各页自行铺设（`hazeSource`）；选了照片则由 nav 层 [AppBackground] 整窗铺满，
 *   此时本函数不再被调用（背景由照片提供，毛玻璃直接采样照片）。
 * - 抽公共前：该渐变背景是 `BookkeepingScreen` 的 private `DefaultBackgroundLayer`。
 *   三页共用，统一抽到 `ui/component`，消除重复（DRY）。
 * - 纯色会让玻璃无从「模糊」，看起来像没改过的白卡，故用 `Brush.verticalGradient` 渐变。
 *
 * @param hazeState 毛玻璃状态持有者（[HazeState]），渐变层注册为 blur source
 * @param modifier 外部修饰符，默认 [Modifier]，渐变层会强制 `fillMaxSize` 铺满
 */
@Composable
fun DefaultHazeBackground(hazeState: HazeState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .hazeSource(hazeState)
    )
}
