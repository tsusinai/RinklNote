package com.example.rinklnote.ui.component

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.ui.util.rememberPressHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

data class KeypadContextItem(
    val label: String,
    val iconRes: Int
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
    modifier: Modifier = Modifier
) {
    val isExpense = billType == "EXPENSE"
    val panelShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    val panelSurface = if (hazeState != null) {
        Modifier.hazeEffect(hazeState, RinklCardFrostedStyle)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface)
    }
    val haptics = rememberPressHaptics()
    val tapHaptic = { haptics.tap() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(panelShape)
            .then(panelSurface)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

        if (showRemark && onRemarkChange != null) {
            Spacer(modifier = Modifier.height(8.dp))
            InlineRemarkField(
                remark = remark,
                onRemarkChange = onRemarkChange
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            KeyButton("1", onClick = { tapHaptic(); onDigit("1") })
            KeyButton("2", onClick = { tapHaptic(); onDigit("2") })
            KeyButton("3", onClick = { tapHaptic(); onDigit("3") })
            KeyButton("⌫", onClick = { tapHaptic(); onBackspace() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            KeyButton("4", onClick = { tapHaptic(); onDigit("4") })
            KeyButton("5", onClick = { tapHaptic(); onDigit("5") })
            KeyButton("6", onClick = { tapHaptic(); onDigit("6") })
            Spacer(modifier = Modifier.width(85.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            KeyButton("7", onClick = { tapHaptic(); onDigit("7") })
            KeyButton("8", onClick = { tapHaptic(); onDigit("8") })
            KeyButton("9", onClick = { tapHaptic(); onDigit("9") })
            Spacer(modifier = Modifier.width(85.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            KeyButton("归零", textSize = 16, onClick = { tapHaptic(); onClear() })
            KeyButton("0", onClick = { tapHaptic(); onDigit("0") })
            KeyButton(".", onClick = { tapHaptic(); onDigit(".") })
            ConfirmButton(
                enabled = confirmEnabled,
                onClick = {
                    tapHaptic()
                    onConfirm()
                }
            )
        }
    }
}

@Composable
private fun AmountDisplay(
    amount: String,
    isExpense: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = amount.ifEmpty { "0.00" },
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
        )
    }
}

@Composable
private fun TypeToggle(
    isExpense: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(38.dp)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isExpense) "支出" else "收入",
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
        )
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
            tint = Color.Unspecified
        )
        Text(
            text = item.label,
            fontSize = 11.sp,
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
    val shape = RoundedCornerShape(14.dp)
    val keyboard = LocalSoftwareKeyboardController.current

    BasicTextField(
        value = remark,
        onValueChange = onRemarkChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f), shape)
            .padding(horizontal = 14.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
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
                        text = "输入备注...",
                        fontSize = 16.sp,
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
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(85.dp)
            .height(36.dp)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
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
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(85.dp)
            .height(36.dp)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "确认",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
        )
    }
}
