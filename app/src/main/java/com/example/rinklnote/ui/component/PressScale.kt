package com.example.rinklnote.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.example.rinklnote.ui.theme.Motion

/** 按下时的缩放比例（轻微下压，避免浮夸）。 */
private const val PressedScale = 0.97f

/**
 * 可复用的按压缩放修饰符：按下时整体缩放到 [PressedScale]，松开后用 Motion 令牌回弹。
 *
 * 用法：把同一个 [InteractionSource] 同时交给可点击组件与本修饰符，例如：
 * ```
 * val src = remember { MutableInteractionSource() }
 * Button(
 *     onClick = onClick,
 *     interactionSource = src,
 *     modifier = Modifier.pressScale(src)
 * ) { ... }
 * ```
 *
 * 说明：
 * - 仅做视觉缩放（graphicsLayer），不影响布局尺寸与点击区域，也不会打断手势；
 * - 动画规格统一取 [Motion.PressScale]，不要在调用处自备 tween/spring；
 * - 只建议用在明显的大按钮（主 CTA / 圆形主按钮）上，列表行与小图标不要套用，
 *   且组件自身禁用（enabled=false）时不会产生按压事件，缩放自然保持 1f。
 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PressedScale else 1f,
        animationSpec = Motion.PressScale,
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
