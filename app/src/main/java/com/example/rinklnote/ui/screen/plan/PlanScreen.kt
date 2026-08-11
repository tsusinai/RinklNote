package com.example.rinklnote.ui.screen.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.viewmodel.BudgetEvent
import com.example.rinklnote.ui.viewmodel.BudgetViewModel

@Composable
fun PlanScreen(viewModel: BudgetViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceHidden by BalancePrivacy.hidden.collectAsStateWithLifecycle()
    var showBudgetKeypad by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "计划",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        BudgetCard(
            state = state,
            hidden = balanceHidden,
            onClick = { showBudgetKeypad = true }
        )
    }

    if (showBudgetKeypad) {
        BudgetKeypadOverlay(
            initialAmount = state.budget?.amount?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: "",
            onConfirm = { amount ->
                showBudgetKeypad = false
                viewModel.onEvent(BudgetEvent.SetBudget(amount))
            },
            onDismiss = { showBudgetKeypad = false }
        )
    }
}

@Composable
private fun BudgetCard(
    state: com.example.rinklnote.ui.viewmodel.BudgetState,
    hidden: Boolean,
    onClick: () -> Unit
) {
    val budget = state.budget
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("本月预算", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            if (budget == null) {
                Text("设置", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    text = if (hidden) "***" else "¥${budget.amount.toBigDecimal().stripTrailingZeros().toPlainString()}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (budget == null) {
            Text("点击设置本月预算", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val over = state.isOverBudget
            val progressColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val spentText = if (hidden) "***" else "¥${String.format("%.2f", state.monthExpense)}"
            val budgetText = if (hidden) "***" else "¥${String.format("%.2f", budget.amount)}"
            val percent = if (budget.amount > 0) (state.monthExpense / budget.amount * 100).toInt() else 0

            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (hidden) "已花 *** / 预算 *** ($percent%)" else "已花 $spentText / 预算 $budgetText ($percent%)",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "本月剩余 ${state.remainingDays} 天",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (over) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "已超预算 ¥${String.format("%.2f", state.overBudgetBy)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BudgetKeypadOverlay(
    initialAmount: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    var amount by remember { mutableStateOf(initialAmount) }

    fun confirmEdit() {
        val value = amount.toDoubleOrNull() ?: return
        onConfirm(value)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("设置预算", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "请输入本月预算金额",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            NumericKeypad(
                amount = amount,
                billType = "EXPENSE",
                remark = "",
                onDigit = { digit ->
                    val newAmount = if (digit == "." && amount.contains(".")) amount else amount + digit
                    amount = newAmount
                },
                onClear = { amount = "" },
                onBackspace = { amount = amount.dropLast(1) },
                onToggleType = {},
                onRemarkClick = {},
                onConfirm = ::confirmEdit
            )
        }
    }
}
