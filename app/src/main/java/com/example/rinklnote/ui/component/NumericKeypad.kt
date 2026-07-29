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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.IncomeGreen

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
    onRemarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isExpense = billType == "EXPENSE"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row: Amount display + Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Amount display — matches Count box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = amount.ifEmpty { "0.00" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
                )
            }

            // Toggle — matches InOrOut
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggleType() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isExpense) "支出" else "收入",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Remark field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onRemarkClick() },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = remark.ifBlank { "点击输入备注..." },
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = if (remark.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Black,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Row 1: 1 2 3 Backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KeyButton("1", onClick = { onDigit("1") })
            KeyButton("2", onClick = { onDigit("2") })
            KeyButton("3", onClick = { onDigit("3") })
            KeyButton("⌫", onClick = onBackspace)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 2: 4 5 6 -
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KeyButton("4", onClick = { onDigit("4") })
            KeyButton("5", onClick = { onDigit("5") })
            KeyButton("6", onClick = { onDigit("6") })
            Spacer(modifier = Modifier.width(85.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 3: 7 8 9
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KeyButton("7", onClick = { onDigit("7") })
            KeyButton("8", onClick = { onDigit("8") })
            KeyButton("9", onClick = { onDigit("9") })
            KeyButton(" ", onClick = {})
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 4: 归零 0 . 确认
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KeyButton("归零", textSize = 16, onClick = onClear)
            KeyButton("0", onClick = { onDigit("0") })
            KeyButton(".", onClick = { onDigit(".") })
            ConfirmButton(onClick = onConfirm)
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    textSize: Int = 20,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(85.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = textSize.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ConfirmButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(85.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "确认",
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
