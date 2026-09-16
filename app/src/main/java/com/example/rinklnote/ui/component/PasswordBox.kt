package com.example.rinklnote.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.PasswordRules
import kotlinx.coroutines.launch

/**
 * 自定义密码输入框（2026-09-17 优化登录方式）——登录 / 注册 / 改密码三处统一换用。
 *
 * 设计要点（大胆但全部走主题令牌，不写死颜色）：
 * - 圆点遮蔽：隐藏态用自绘圆点逐个弹入（spring 低阻尼回弹），删除即时收缩；
 * - 可见性切换：右侧手绘「眼睛」图标（Canvas），瞳孔随开合缩放 + 闭眼斜杠淡入；
 * - 聚焦描边走 [LocalRinklColors] 主题色槽，错误描边走 error 语义色；
 * - [showStrength] 开启时（注册 / 改密码等「新设定」场景）显示实时强度条：弱=支出红、
 *   中=主题色、强=收入绿（复用收支两槽令牌），规则见 [PasswordRules.strength]。
 *
 * 安全：BasicTextField 固定 [KeyboardType.Password]（系统安全键盘 + 不进输入法词库），
 * 隐藏态真实文本透明、仅展示自绘圆点，截图 / 录屏不出现明文。
 */
@Composable
fun PasswordBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "密码",
    placeholder: String = "请输入密码",
    /** 「新设定」场景（注册 / 改密码）开启实时强度提示。 */
    showStrength: Boolean = false,
    isError: Boolean = false,
    errorMessage: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeDone: () -> Unit = {}
) {
    var revealed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val rinkl = LocalRinklColors.current

    Column(modifier = modifier) {
        // 小标签：与 Web 登录页 field-label 同构（12sp 半粗、弱化色）
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        // 描边色：错误 > 聚焦（主题色） > 未聚焦（透明 = 无边框，与 App「默认不描边」基调一致）
        val borderColor by animateColorAsState(
            targetValue = when {
                isError -> MaterialTheme.colorScheme.error
                focused -> rinkl.themeColor
                else -> Color.Transparent
            },
            animationSpec = Motion.SelectColor,
            label = "passwordBorder"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .border(width = 1.5.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
                .heightIn(min = 54.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                // 隐藏态：真实文本透明，圆点由自绘层展示；光标仍按真实位置绘制
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 16.sp,
                    letterSpacing = 0.5.sp,
                    color = if (revealed) rinkl.fontColor else Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(
                    // 安全输入类型：系统按密码处理（不进词库、不弹明文建议）
                    keyboardType = KeyboardType.Password,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(onDone = { onImeDone() }),
                cursorBrush = SolidColor(rinkl.themeColor),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        inner()
                        if (!revealed && value.isNotEmpty()) {
                            // 自绘圆点层：逐个弹入（见 [DotsRow]）
                            DotsRow(count = value.length, color = rinkl.fontColor)
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.size(8.dp))
            EyeToggle(
                revealed = revealed,
                tint = if (isError) MaterialTheme.colorScheme.error
                else if (focused) rinkl.themeColor
                else MaterialTheme.colorScheme.onSurfaceVariant,
                onToggle = { revealed = !revealed }
            )
        }

        // 实时强度提示（仅新设定场景 + 已输入时展示）
        if (showStrength && value.isNotEmpty()) {
            StrengthMeter(password = value)
        }

        if (isError && errorMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorMessage,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 圆点遮蔽层：每个字符对应一枚圆点，新圆点以 spring（低阻尼）从 0 弹到 1，
 * 形成「逐个弹入」的微动画；删除时即时移除（不回放，保持干脆）。
 */
@Composable
private fun DotsRow(count: Int, color: Color) {
    val dots = remember { mutableStateListOf<Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(count) {
        while (dots.size < count) {
            val dot = Animatable(0f)
            dots.add(dot)
            launch {
                dot.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
                )
            }
        }
        while (dots.size > count) dots.removeAt(dots.lastIndex)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { dot ->
            val s = dot.value.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .scale(scaleX = s, scaleY = s)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.85f))
            )
        }
    }
}

/**
 * 手绘「眼睛」可见性开关：眼眶为杏仁形双弧，瞳孔随开合缩放，
 * 闭眼时斜杠淡入——不引入图标库，颜色完全由调用方令牌决定。
 */
@Composable
private fun EyeToggle(revealed: Boolean, tint: Color, onToggle: () -> Unit) {
    // open: 0 = 闭眼 → 1 = 睁眼
    val open by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.DurationPress * 2, easing = Motion.IndicatorEasing),
        label = "eyeOpen"
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(
                onClickLabel = if (revealed) "隐藏密码" else "显示密码",
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            val w = size.width
            val h = size.height
            // 眼眶：上下两条三次贝塞尔近似杏仁形
            val outline = Path().apply {
                moveTo(0f, h / 2f)
                cubicTo(w * 0.25f, 0f, w * 0.75f, 0f, w, h / 2f)
                cubicTo(w * 0.75f, h, w * 0.25f, h, 0f, h / 2f)
                close()
            }
            drawPath(outline, color = tint, style = stroke)
            // 瞳孔：随开合缩放（闭眼时缩为 0 不绘制）
            if (open > 0.05f) {
                drawCircle(
                    color = tint,
                    radius = (size.minDimension / 5.5f) * open,
                    center = center
                )
            }
            // 闭眼斜杠：随开眼淡出
            if (open < 0.95f) {
                drawLine(
                    color = tint.copy(alpha = 1f - open),
                    start = Offset(w * 0.1f, h * 0.9f),
                    end = Offset(w * 0.9f, h * 0.1f),
                    strokeWidth = 1.8.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * 强度条（弱 / 中 / 强）：三段圆角条 + 单字标签，
 * 颜色复用收支两槽令牌（弱=支出红 / 中=主题色 / 强=收入绿），不引入新颜色。
 */
@Composable
private fun StrengthMeter(password: String) {
    val rinkl = LocalRinklColors.current
    val strength = remember(password) { PasswordRules.strength(password) }
    val level = when (strength) {
        PasswordRules.Strength.WEAK -> 1
        PasswordRules.Strength.MEDIUM -> 2
        PasswordRules.Strength.STRONG -> 3
    }
    val barColor = when (strength) {
        PasswordRules.Strength.WEAK -> rinkl.expenseColor
        PasswordRules.Strength.MEDIUM -> rinkl.themeColor
        PasswordRules.Strength.STRONG -> rinkl.incomeColor
    }
    val label = when (strength) {
        PasswordRules.Strength.WEAK -> "弱"
        PasswordRules.Strength.MEDIUM -> "中"
        PasswordRules.Strength.STRONG -> "强"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < level) barColor else barColor.copy(alpha = 0.16f))
            )
        }
        Text(text = label, fontSize = 11.sp, color = barColor)
        Text(text = "强度", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}
