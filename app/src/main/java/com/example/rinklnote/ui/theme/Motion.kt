package com.example.rinklnote.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** 全局动效令牌：按压即时响应、状态切换柔和落定、退出比进入更短。 */
object Motion {
    // 首屏最多分四组入场，最后一组在 340ms 内完成；延迟交给动画系统随系统比例缩放。
    fun stagedReveal(stage: Int): FiniteAnimationSpec<Float> =
        tween(220, delayMillis = stage.coerceIn(0, 3) * 40, easing = LinearOutSlowInEasing)
    val Selection: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 650f)
    val SelectionWidth: FiniteAnimationSpec<Dp> = tween(180, easing = FastOutSlowInEasing)
    // 平静、克制：按压即时，入场缓出，退出加速；交互不出现弹性过冲。
    val Interaction: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
    val Reveal: FiniteAnimationSpec<Float> = tween(220, easing = LinearOutSlowInEasing)
    val Dismiss: FiniteAnimationSpec<Float> = tween(150, easing = FastOutLinearInEasing)
    val ContentSlide: FiniteAnimationSpec<IntOffset> = tween(220, easing = FastOutSlowInEasing)
    val NavigationSlide: FiniteAnimationSpec<IntOffset> = tween(320, easing = LinearOutSlowInEasing)
    val NavigationExit: FiniteAnimationSpec<IntOffset> = tween(220, easing = FastOutLinearInEasing)
    val Progress: FiniteAnimationSpec<Float> = tween(360, easing = FastOutSlowInEasing)
    // Durations (ms)
    const val DurationSheet = 250        // keypad & slide-up overlays
    const val DurationDrawer = 240       // quick-add drawer enter / exit
    const val DurationIndicator = 300    // bottom bar indicator
    const val DurationExpand = 220       // in-card expand / collapse & chevron rotate
    const val DurationChartDraw = 1000
    const val DurationChartUpdate = 500
    const val DurationBreath = 650       // voice-bar breathing loop
    const val DurationPress = 120        // 按压缩放反馈（PressScale 修饰符）
    // Easing shared with Web's --ease
    val IndicatorEasing = FastOutSlowInEasing

    // Sheets / keypad: smooth tween in and out (no spring overshoot / bounce)
    val SheetEnter: FiniteAnimationSpec<IntOffset> = tween(DurationSheet, easing = IndicatorEasing)
    val SheetExit: FiniteAnimationSpec<IntOffset> = tween(180, easing = FastOutLinearInEasing)

    // QuickAdd drawer (horizontal)：与输入法串行切换，固定时长便于精准接棒。
    val DrawerEnter: FiniteAnimationSpec<IntOffset> = tween(DurationDrawer, easing = IndicatorEasing)
    val DrawerExit: FiniteAnimationSpec<IntOffset> = tween(170, easing = FastOutLinearInEasing)

    // Bottom nav indicator
    val Indicator: FiniteAnimationSpec<Dp> = tween(DurationIndicator, easing = IndicatorEasing)

    // iOS-style swipe reveal settle
    val SwipeSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 400f)

    // Chart draw / data-update
    val ChartDraw: FiniteAnimationSpec<Float> = tween(DurationChartDraw)
    val ChartUpdate: FiniteAnimationSpec<Float> = tween(DurationChartUpdate)

    // In-card expand / collapse (IntSize) and fade / rotate (Float)
    val Expand: FiniteAnimationSpec<IntSize> = tween(DurationExpand, easing = IndicatorEasing)
    val Fade: FiniteAnimationSpec<Float> = tween(DurationExpand, easing = IndicatorEasing)

    // 按压缩放（PressScale 修饰符）：短促干脆的按下/回弹，无过冲
    val PressScale: FiniteAnimationSpec<Float> = tween(DurationPress, easing = IndicatorEasing)

    // 选中态颜色过渡（Color）：chip 底色 / radio 圆点填充在选中切换时交叉淡化，避免色块跳变
    val SelectColor: FiniteAnimationSpec<Color> = tween(DurationExpand, easing = IndicatorEasing)

    // Card height auto-resize (drag settle / expand) — same spring feel as row shift
    val ContentResize: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

    // 编辑页：短距离位移和柔和缓出，尺寸变化不回弹，避免相邻表单跳动。
    val EditorResize: FiniteAnimationSpec<IntSize> = tween(240, easing = IndicatorEasing)
    val EditorSlide: FiniteAnimationSpec<IntOffset> = tween(220, easing = IndicatorEasing)
    val EditorFade: FiniteAnimationSpec<Float> = tween(160, easing = IndicatorEasing)
    val EditorSelection: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f)
    val PressRelease: FiniteAnimationSpec<Float> = tween(180, easing = LinearOutSlowInEasing)
    val SliderReveal: FiniteAnimationSpec<Float> = tween(240, easing = LinearOutSlowInEasing)
    val SliderDismiss: FiniteAnimationSpec<Float> = tween(160, easing = FastOutLinearInEasing)
    val SliderSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 550f)
}
