package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.ui.component.NumericKeypad

@Composable
fun BillEditOverlay(
    bill: Bill,
    expenseCategories: List<Category>,
    incomeCategories: List<Category>,
    accounts: List<Account>,
    onCancel: () -> Unit,
    onConfirm: (Bill) -> Unit
) {
    BackHandler { onCancel() }

    var amount by remember(bill.id) { mutableStateOf(bill.amount.toBigDecimal().stripTrailingZeros().toPlainString()) }
    var billType by remember(bill.id) { mutableStateOf(bill.billType) }
    var selectedCategory by remember(bill.id) {
        val list = if (bill.billType == "EXPENSE") expenseCategories else incomeCategories
        mutableStateOf(list.firstOrNull { it.id == bill.categoryId } ?: list.firstOrNull())
    }
    var selectedAccount by remember(bill.id) {
        mutableStateOf(accounts.firstOrNull { it.id == bill.accountId } ?: accounts.firstOrNull())
    }
    var remark by remember(bill.id) { mutableStateOf(bill.remark ?: "") }

    val visibleCategories = if (billType == "EXPENSE") expenseCategories else incomeCategories

    val onToggleType: () -> Unit = {
        billType = if (billType == "EXPENSE") "INCOME" else "EXPENSE"
        selectedCategory = (if (billType == "EXPENSE") expenseCategories else incomeCategories).firstOrNull()
    }

    fun confirmEdit() {
        val amountVal = amount.toDoubleOrNull() ?: return
        val cat = visibleCategories.find { it.id == selectedCategory?.id } ?: return
        val acct = selectedAccount ?: return
        onConfirm(
            bill.copy(
                amount = amountVal,
                billType = billType,
                categoryId = cat.id,
                categoryName = cat.name,
                subCategoryName = null,
                accountId = acct.id,
                remark = remark.ifBlank { null }
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("编辑账单", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onCancel) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Category & account pickers
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text("分类", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleCategories.forEach { cat ->
                    val selected = cat.id == selectedCategory?.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            cat.name,
                            fontSize = 14.sp,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("账户", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                accounts.forEach { acct ->
                    val selected = acct.id == selectedAccount?.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable { selectedAccount = acct }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            acct.name,
                            fontSize = 14.sp,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Numeric keypad (amount / type toggle / remark / confirm)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            NumericKeypad(
                amount = amount,
                billType = billType,
                remark = remark,
                onDigit = { digit ->
                    val newAmount = if (digit == "." && amount.contains(".")) amount else amount + digit
                    amount = newAmount
                },
                onClear = { amount = "" },
                onBackspace = { amount = amount.dropLast(1) },
                onToggleType = onToggleType,
                onRemarkClick = {},
                onConfirm = ::confirmEdit
            )
        }
    }
}
