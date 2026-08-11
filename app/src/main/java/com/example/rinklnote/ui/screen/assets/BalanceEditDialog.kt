package com.example.rinklnote.ui.screen.assets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.ui.component.NumericKeypad

@Composable
fun BalanceEditDialog(
    account: Account,
    onConfirm: (Account) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    var amount by remember(account.id) {
        mutableStateOf(account.balance.toBigDecimal().stripTrailingZeros().toPlainString())
    }

    fun confirmEdit() {
        val value = amount.toDoubleOrNull() ?: return
        onConfirm(account.copy(balance = value))
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
            Column {
                Text("编辑余额", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(account.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "请输入账户余额",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Reuse the stateless NumericKeypad for amount entry
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
