package com.example.rinklnote.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Central motion tokens — single source of truth for the app's animation specs.
 * Duration in milliseconds; springs/tweens typed per target value type.
 * The Web side mirrors FastOutSlowInEasing via --ease: cubic-bezier(.4,0,.2,1).
 */
object Motion {
    // Durations (ms)
    const val DurationSheet = 250        // keypad & slide-up overlays
    const val DurationDrawer = 240       // quick-add drawer enter / exit
    const val DurationIndicator = 300    // bottom bar indicator
    const val DurationExpand = 220       // in-card expand / collapse & chevron rotate
    const val DurationChartDraw = 1000
    const val DurationChartUpdate = 500
    const val DurationBreath = 650       // voice-bar breathing loop
    // Easing shared with Web's --ease
    val IndicatorEasing = FastOutSlowInEasing

    // Sheets / keypad: smooth tween in and out (no spring overshoot / bounce)
    val SheetEnter: FiniteAnimationSpec<IntOffset> = tween(DurationSheet, easing = IndicatorEasing)
    val SheetExit: FiniteAnimationSpec<IntOffset> = tween(DurationSheet, easing = IndicatorEasing)

    // QuickAdd drawer (horizontal)：与输入法串行切换，固定时长便于精准接棒。
    val DrawerEnter: FiniteAnimationSpec<IntOffset> = tween(DurationDrawer, easing = IndicatorEasing)
    val DrawerExit: FiniteAnimationSpec<IntOffset> = tween(DurationDrawer, easing = IndicatorEasing)

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

    // Card height auto-resize (drag settle / expand) — same spring feel as row shift
    val ContentResize: FiniteAnimationSpec<IntSize> = spring(stiffness = Spring.StiffnessMediumLow)
}
