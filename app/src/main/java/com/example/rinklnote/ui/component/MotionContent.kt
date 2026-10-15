package com.example.rinklnote.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.rinklnote.ui.theme.Motion

/** 浮层入场只改变绘制，不让尺寸、焦点或滚动位置随动画变化。 */
fun Modifier.motionReveal(stage: Int = 0): Modifier = composed {
    val progress = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(Unit) { progress.animateTo(1f, Motion.stagedReveal(stage)) }
    graphicsLayer {
        alpha = progress.value
        scaleX = 0.98f + 0.02f * progress.value
        scaleY = scaleX
        translationY = distance * (1f - progress.value)
    }
}

/** 保留标记占位，只平滑改变绘制；选项切换不会挤动标签和相邻控件。 */
fun Modifier.motionSelection(selected: Boolean): Modifier = composed {
    val progress by animateFloatAsState(if (selected) 1f else 0f, Motion.Selection, label = "选中标记显隐")
    graphicsLayer {
        alpha = progress
        scaleX = 0.85f + 0.15f * progress
        scaleY = scaleX
    }
}

/** 只传状态键；表单输入值不做整体切换，避免打断输入和无障碍焦点。 */
@Composable
fun <T> MotionContent(targetState: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val distance = with(LocalDensity.current) { 6.dp.roundToPx() }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(Motion.Reveal) + slideInVertically(Motion.ContentSlide) { distance })
                .togetherWith(fadeOut(Motion.Dismiss) + slideOutVertically(Motion.ContentSlide) { -distance })
                .using(SizeTransform { _, _ -> Motion.ContentResize })
        },
        label = "内容状态过渡"
    ) { content(it) }
}

/** 列表增删和重排跟随稳定键；不在每次滚动进入视口时重复播放入场。 */
@Composable
fun LazyItemScope.MotionListItem(content: @Composable () -> Unit) {
    Column(Modifier.animateItem(fadeInSpec = Motion.Reveal, placementSpec = Motion.ContentSlide, fadeOutSpec = Motion.Reveal)) {
        content()
    }
}

/** 共享浮层入场，保留系统返回、外部点击和窗口属性的原有行为。 */
@Composable
fun RinklDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        Box(Modifier.motionReveal()) { content() }
    }
}

@Composable
fun animatedProgress(target: Float): Float {
    val progress by animateFloatAsState(target.coerceIn(0f, 1f), Motion.Progress, label = "进度平滑变化")
    return progress
}

@Composable
fun selectionColor(selected: Boolean, active: Color, inactive: Color): Color {
    val color by animateColorAsState(if (selected) active else inactive, Motion.SelectColor, label = "选项颜色过渡")
    return color
}
