package com.example.rinklnote.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * Central motion tokens — single source of truth for the app's animation specs.
 * Duration in milliseconds; springs/tweens typed per target value type.
 * The Web side mirrors FastOutSlowInEasing via --ease: cubic-bezier(.4,0,.2,1).
 */
object Motion {
    // Durations (ms)
    const val DurationSheet = 250        // keypad & slide-up overlays
    const val DurationIndicator = 300    // bottom bar indicator
    const val DurationChartDraw = 1000
    const val DurationChartUpdate = 500

    // Easing shared with Web's --ease
    val IndicatorEasing = FastOutSlowInEasing

    // Sheets / keypad: spring in, tween out
    val SheetEnter: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.8f, stiffness = 300f)
    val SheetExit: FiniteAnimationSpec<IntOffset> = tween(DurationSheet)

    // QuickAdd drawer (horizontal)
    val DrawerEnter: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow
    )
    val DrawerExit: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow
    )

    // Bottom nav indicator
    val Indicator: FiniteAnimationSpec<Dp> = tween(DurationIndicator, easing = IndicatorEasing)

    // iOS-style swipe reveal settle
    val SwipeSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 400f)

    // Two-phase confirm check pop
    val CheckPop: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow
    )

    // Chart draw / data-update
    val ChartDraw: FiniteAnimationSpec<Float> = tween(DurationChartDraw)
    val ChartUpdate: FiniteAnimationSpec<Float> = tween(DurationChartUpdate)
}
