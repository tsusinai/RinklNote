package com.example.rinklnote.ui.screen.bookkeeping

import kotlin.math.abs

internal enum class SliderDirection { LEFT, UP, RIGHT }

/** 用累计位移判断方向；中心保留取消区，下滑不触发操作。 */
internal fun resolveSliderDirection(x: Float, y: Float, threshold: Float): SliderDirection? = when {
    maxOf(abs(x), abs(y)) < threshold -> null
    abs(x) > abs(y) -> if (x < 0f) SliderDirection.LEFT else SliderDirection.RIGHT
    y < 0f -> SliderDirection.UP
    else -> null
}
