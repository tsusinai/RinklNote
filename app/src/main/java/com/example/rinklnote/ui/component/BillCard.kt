package com.example.rinklnote.ui.component

import java.util.Locale


import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.theme.AxisLabelGray
import com.example.rinklnote.ui.theme.DarkIncomeGreen
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.util.toDateString
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/** 一天一张卡：日期头 + 该日所有账单。内部经 derivedStateOf 读取 revealed/menu，
 *  侧滑只重组真正受影响的行，而非整卡。 */
@SuppressLint("DefaultLocale")
@OptIn(ExperimentalHazeMaterialsApi::class)
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
    hazeState: HazeState,
    backgroundUri: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(hazeState, backgroundUri, RoundedCornerShape(15.dp)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${date.toDateString()} $dayOfWeek",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            val incomeGreen = if (isSystemInDarkTheme()) DarkIncomeGreen else IncomeGreen
            val sign = if (totalAmount >= 0) "+" else "-"
            // 缓存金额格式化（重组时不重复 String.format）。
            val totalText = remember(totalAmount) {
                String.format(Locale.US, "%s¥%.2f", sign, kotlin.math.abs(totalAmount))
            }
            Text(
                text = totalText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (totalAmount >= 0) incomeGreen else MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxWidth()) { drawLine(color = AxisLabelGray, start = Offset(x=0.dp.toPx(),y = 0.dp.toPx()),  end = Offset(size.width - 6.dp.toPx(), 0f),)}

        bills.forEach { bill ->
            val revealed by remember(bill.id) { derivedStateOf { revealedBillId == bill.id } }
            val menuVisible by remember(bill.id) { derivedStateOf { menuBill?.id == bill.id } }
            Box {
                SwipeableBillItem(
                    revealed = revealed,
                    onRevealChange = { r -> onRevealChange(if (r) bill.id else null) },
                    onDelete = { onDelete(bill) }
                ) {
                    BillItem(
                        categoryName = bill.subCategoryName ?: bill.categoryName,
                        amount = bill.amount,
                        billType = bill.billType.value,
                        remark = bill.remark,
                        onClick = {
                            // 点已展开的行收起；点未展开的行进入编辑
                            if (revealed) onRevealChange(null) else onEdit(bill)
                        },
                        onLongPress = { onMenuChange(bill) }
                    )
                }
                DropdownMenu(
                    expanded = menuVisible,
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
            Spacer(modifier = Modifier.height(3.dp))
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
    // 缓存金额格式化（重组时不重复 String.format）。
    val amountText = remember(amount, isExpense) {
        String.format(Locale.US, "%s¥%.2f", if (isExpense) "-" else "+", amount)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
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
            text = amountText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else incomeGreen
        )
    }
}
