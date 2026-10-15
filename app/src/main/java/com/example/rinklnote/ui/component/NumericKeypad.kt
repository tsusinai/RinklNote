package com.example.rinklnote.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.example.rinklnote.R
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.rememberPressHaptics
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import dev.chrisbanes.haze.hazeEffect

@androidx.compose.runtime.Immutable
data class KeypadContextItem(
    val label: String,
    val iconRes: Int,
    val iconTint: Color? = null
)

@Composable
fun NumericKeypad(
    amount: String,
    billType: String,
    remark: String,
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    onToggleType: () -> Unit,
    onRemarkChange: ((String) -> Unit)? = null,
    showTypeToggle: Boolean = true,
    showRemark: Boolean = true,
    confirmEnabled: Boolean = true,
    contextItems: List<KeypadContextItem> = emptyList(),
    hazeState: HazeState? = null,
    bottomPadding: Dp = 16.dp,
    compact: Boolean = false,
    editorMode: Boolean = false,
    confirmLabel: String = "确认",
    modifier: Modifier = Modifier
) {
    val isExpense = billType == "EXPENSE"
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyHeight = if (editorMode) 48.dp else 36.dp
    val panelShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    val panelSurface = if (hazeState != null) {
        Modifier.hazeEffect(hazeState, RinklCardFrostedStyle)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
    }
    val haptics = rememberPressHaptics()
    val tapHaptic = { haptics.tap() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 自定义数字键盘 + 系统 IME 混合场景：备注输入框唤起系统输入法时，
            // 键盘面板整体抬升到输入法上方，备注框仍可见可编辑；
            // 输入法收起时 imePadding 为 0，纯数字键盘场景布局完全不变。
            .imePadding()
            .clip(panelShape)
            .then(panelSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(start = 16.dp, end = 16.dp, top = if (compact) 6.dp else 8.dp, bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (editorMode && showTypeToggle) {
            KeypadTypeSelector(isExpense) { tapHaptic(); onToggleType() }
        }
        if (!editorMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AmountDisplay(
                    amount = amount,
                    isExpense = isExpense,
                    modifier = Modifier.weight(1f)
                )

                if (showTypeToggle) {
                    TypeToggle(
                        isExpense = isExpense,
                        onClick = {
                            tapHaptic()
                            onToggleType()
                        }
                    )
                } else if (contextItems.isNotEmpty()) {
                    ContextRow(contextItems)
                }
            }

        }

        if (showRemark && onRemarkChange != null) {
            Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
            InlineRemarkField(
                remark = remark,
                onRemarkChange = onRemarkChange
            )
        }

        AnimatedVisibility(
            visible = !editorMode || !imeVisible,
            enter = fadeIn(Motion.EditorFade) + expandVertically(Motion.EditorResize),
            exit = fadeOut(Motion.Dismiss) + shrinkVertically(Motion.EditorResize)
        ) {
            if (editorMode) {
                EditorNumberGrid(confirmEnabled, confirmLabel,
                    onDigit = { tapHaptic(); onDigit(it) },
                    onClear = { tapHaptic(); onClear() },
                    onBackspace = { tapHaptic(); onBackspace() },
                    onConfirm = { tapHaptic(); onConfirm() })
            } else {
                Column {
                    Spacer(modifier = Modifier.height(if (compact) 6.dp else 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        KeyButton("1", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("1") })
                        KeyButton("2", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("2") })
                        KeyButton("3", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("3") })
                        KeyButton("⌫", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onBackspace() })
                    }

                    Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        KeyButton("4", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("4") })
                        KeyButton("5", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("5") })
                        KeyButton("6", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("6") })
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        KeyButton("7", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("7") })
                        KeyButton("8", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("8") })
                        KeyButton("9", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("9") })
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        KeyButton("归零", textSize = 16, modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onClear() })
                        KeyButton("0", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit("0") })
                        KeyButton(".", modifier = Modifier.weight(1f), minHeight = keyHeight, onClick = { tapHaptic(); onDigit(".") })
                        ConfirmButton(
                            enabled = confirmEnabled,
                            label = confirmLabel,
                            filled = editorMode,
                            minHeight = keyHeight,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                tapHaptic()
                                onConfirm()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 收支选择集成在输入面板内，选中项再次点击不改变账单类型。 */
@Composable
private fun KeypadTypeSelector(isExpense: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(true, false).forEach { expense ->
            val active = isExpense == expense
            val background by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                Motion.SelectColor, label = "键盘收支选择背景")
            val colors = LocalRinklColors.current
            val textColor by animateColorAsState(
                if (active) { if (expense) colors.expenseColor else colors.incomeColor }
                else MaterialTheme.colorScheme.onSurface,
                Motion.SelectColor, label = "键盘收支选择文字")
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(background).semantics { selected = active }
                .motionClickable(role = Role.RadioButton) { if (!active) onToggle() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(if (expense) "支出" else "收入", fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = textColor)
            }
        }
    }
}

/** 编辑页使用完整的四列键盘，右侧保存键跨三行，避免无意义的空白单元。 */
@Composable
private fun EditorNumberGrid(
    enabled: Boolean,
    label: String,
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptics = rememberPressHaptics()
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("清空", "0", ".")).forEach { keys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { key ->
                        KeyButton(key, textSize = if (key == "清空") 14 else 24, minHeight = 48.dp,
                            modifier = Modifier.weight(1f), onClick = {
                                haptics.tap()
                                if (key == "清空") onClear() else onDigit(key)
                            })
                    }
                }
            }
        }
        Column(Modifier.weight(1f).height(204.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("⌫", minHeight = 48.dp, modifier = Modifier.fillMaxWidth(), onClick = {
                haptics.tap()
                onBackspace()
            })
            ConfirmButton(enabled, label, filled = true, minHeight = 48.dp,
                modifier = Modifier.fillMaxWidth().weight(1f), onClick = {
                    haptics.tap()
                    onConfirm()
                })
        }
    }
}

@Composable
private fun AmountDisplay(
    amount: String,
    isExpense: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(15.dp)
    val amountColor by animateColorAsState(
        if (isExpense) MaterialTheme.colorScheme.tertiary else LocalRinklColors.current.incomeColor,
        Motion.SelectColor, label = "金额收支颜色"
    )
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = amount.ifEmpty { "0.00" },
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = amountColor
        )
    }
}

@Composable
private fun TypeToggle(
    isExpense: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .width(64.dp)
            .heightIn(min = 38.dp)
            .pressScale(interaction)
            .clip(shape)
            .padding(vertical = 2.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isExpense,
            transitionSpec = {
                (fadeIn(Motion.EditorFade) + slideInVertically(Motion.EditorSlide) { it / 2 })
                    .togetherWith(fadeOut(Motion.EditorFade) + slideOutVertically(Motion.EditorSlide) { -it / 2 })
            },
            label = "收支切换"
        ) { expense ->
            Text(
                text = if (expense) "支出" else "收入",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                color = if (expense) MaterialTheme.colorScheme.tertiary else LocalRinklColors.current.incomeColor
            )
        }
    }
}

@Composable
private fun ContextRow(items: List<KeypadContextItem>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            ContextChip(item)
        }
    }
}

@Composable
private fun ContextChip(item: KeypadContextItem) {
    Row(
        modifier = Modifier
            .widthIn(max = 64.dp)
            .height(24.dp)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(item.iconRes),
            contentDescription = item.label,
            modifier = Modifier.size(16.dp),
            tint = item.iconTint ?: Color.Unspecified
        )
        Text(
            text = item.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InlineRemarkField(
    remark: String,
    onRemarkChange: (String) -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val keyboard = LocalSoftwareKeyboardController.current

    BasicTextField(
        value = remark,
        onValueChange = onRemarkChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f), shape)
            .padding(horizontal = 14.dp),
        textStyle = TextStyle(
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (remark.isEmpty()) {
                    Text(
                        text = "添加备注 · 可选",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun KeyButton(
    text: String,
    textSize: Int = 20,
    minHeight: Dp = 36.dp,
    contentDescriptionText: String? = if (text == "⌫") "退格" else null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .pressScale(interaction)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f), shape)
            .then(
                if (contentDescriptionText != null) Modifier.semantics { contentDescription = contentDescriptionText }
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text == "⌫") {
            Icon(painterResource(R.drawable.ic_backspace), contentDescription = null,
                modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(
            text = text,
            fontSize = textSize.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ConfirmButton(
    enabled: Boolean,
    label: String = "确认",
    filled: Boolean = false,
    minHeight: Dp = 36.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val interaction = remember { MutableInteractionSource() }
    val confirmAlpha by animateFloatAsState(if (enabled) 1f else 0.38f, Motion.EditorFade, label = "确认可用状态")
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .pressScale(interaction)
            .clip(shape)
            .then(if (filled) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f * confirmAlpha)) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = if (filled) 20.sp else 18.sp,
            fontWeight = FontWeight.Medium,
            color = if (filled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = confirmAlpha) else MaterialTheme.colorScheme.primary.copy(alpha = confirmAlpha)
        )
    }
}
