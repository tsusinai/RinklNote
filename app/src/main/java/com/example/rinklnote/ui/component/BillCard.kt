package com.example.rinklnote.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.theme.DarkIncomeGreen
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.util.toDateString

@Composable
fun BillCard(
    date: Long,
    dayOfWeek: String,
    totalAmount: Double,
    bills: List<Bill>,
    revealedBillId: Long?,
    menuBill: Bill?,
    onRevealChange: (Long?) -> Unit,
    onMenuChange: (Bill?) -> Unit,
    onEdit: (Bill) -> Unit,
    onDelete: (Bill) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header: date + total
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${date.toDateString()} $dayOfWeek",
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            val incomeGreen = if (isSystemInDarkTheme()) DarkIncomeGreen else IncomeGreen
            val sign = if (totalAmount >= 0) "+" else "-"
            Text(
                text = String.format("%s¥%.2f", sign, kotlin.math.abs(totalAmount)),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = if (totalAmount >= 0) incomeGreen else MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Items
        bills.forEach { bill ->
            val displayName = bill.subCategoryName ?: bill.categoryName
            val revealed = revealedBillId == bill.id
            Box {
                SwipeableBillItem(
                    revealed = revealed,
                    onRevealChange = { r -> onRevealChange(if (r) bill.id else null) },
                    onDelete = { onDelete(bill) }
                ) {
                    BillItem(
                        categoryName = displayName,
                        amount = bill.amount,
                        billType = bill.billType,
                        remark = bill.remark,
                        onClick = {
                            // Tap an open row to close it; tap a closed row to edit
                            if (revealed) onRevealChange(null) else onEdit(bill)
                        },
                        onLongPress = { onMenuChange(bill) }
                    )
                }
                DropdownMenu(
                    expanded = menuBill?.id == bill.id,
                    onDismissRequest = { onMenuChange(null) }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            onMenuChange(null)
                            onEdit(bill)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            onMenuChange(null)
                            onDelete(bill)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillItem(
    categoryName: String,
    amount: Double,
    billType: String,
    remark: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val incomeGreen = if (isSystemInDarkTheme()) DarkIncomeGreen else IncomeGreen
    val isExpense = billType == "EXPENSE"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isExpense) MaterialTheme.colorScheme.tertiary else incomeGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = categoryName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!remark.isNullOrBlank()) {
                    Text(
                        text = remark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = String.format("%s¥%.2f", if (isExpense) "-" else "+", amount),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else incomeGreen
        )
    }
}
