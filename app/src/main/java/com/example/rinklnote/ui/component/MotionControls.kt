package com.example.rinklnote.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.rememberPressHaptics

/** 普通行和选项只轻压 1.5%，不改变布局、触摸区域或原有点击语义。 */
private fun Modifier.motionPress(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f,
        if (pressed) Motion.PressScale else Motion.PressRelease, label = "轻压反馈")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 单选项保留系统 selected 语义，并复用普通按钮的轻压反馈。 */
fun Modifier.motionSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    motionPress(source).selectable(selected, source, androidx.compose.foundation.LocalIndication.current,
        enabled, role) { haptics.tap(); onClick() }
}

/** 保留原有水波纹及无障碍参数，补齐普通 clickable 的按压动画。 */
fun Modifier.motionClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    motionPress(source).clickable(source, androidx.compose.foundation.LocalIndication.current, enabled, onClickLabel, role) {
        haptics.tap(); onClick()
    }
}

fun Modifier.motionClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    motionPress(source).clickable(source, indication, enabled, onClickLabel, role) {
        haptics.tap(); onClick()
    }
}

/** 按钮继续由 Material 处理禁用、涟漪和语义，动画不延迟业务回调。 */
@Composable
fun RinklButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    androidx.compose.material3.Button({ haptics.tap(); onClick() }, modifier.motionPress(source), enabled, shape, colors, elevation,
        border, contentPadding, source, content)
}

@Composable
fun RinklOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    androidx.compose.material3.OutlinedButton({ haptics.tap(); onClick() }, modifier.motionPress(source), enabled, shape, colors,
        elevation, border, contentPadding, source, content)
}

@Composable
fun RinklTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    androidx.compose.material3.TextButton({ haptics.tap(); onClick() }, modifier.motionPress(source), enabled, shape, colors,
        elevation, border, contentPadding, source, content)
}

@Composable
fun RinklIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberPressHaptics()
    androidx.compose.material3.IconButton({ haptics.tap(); onClick() }, modifier.motionPress(source), enabled, colors, source, content)
}

/** 预算及加载进度变化时缓动到目标，数值计算仍由页面负责。 */
@Composable
fun RinklLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    trackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    strokeCap: StrokeCap = StrokeCap.Round
) {
    val value = animatedProgress(progress())
    androidx.compose.material3.LinearProgressIndicator(progress = { value }, modifier = modifier,
        color = color, trackColor = trackColor, strokeCap = strokeCap)
}

@Composable
fun RinklLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    trackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    strokeCap: StrokeCap = StrokeCap.Round
) {
    androidx.compose.material3.LinearProgressIndicator(modifier = modifier, color = color,
        trackColor = trackColor, strokeCap = strokeCap)
}
