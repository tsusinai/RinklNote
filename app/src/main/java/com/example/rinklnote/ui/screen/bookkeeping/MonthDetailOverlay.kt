package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.rinklnote.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.theme.IncomeGreen

@Composable
fun MonthDetailOverlay(
    visible: Boolean,
    monthLabel: String,
    bills: List<Bill>,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
    ) {
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
                Text(
                    text = monthLabel,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val expenseTotal = bills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }
            val incomeTotal = bills.filter { it.billType == "INCOME" }.sumOf { it.amount }
            val grouped = bills.groupBy { it.categoryName }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                item(key = "totals") { TotalsCard(expenseTotal, incomeTotal) }
                item { Spacer(modifier = Modifier.height(12.dp)) }

                if (bills.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("该月暂无账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                grouped.forEach { (catName, bills) ->
                    val subtotal = bills.sumOf { it.amount }
                    item(key = "cat_$catName") {
                        CategoryHeader(catName, subtotal)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    items(bills, key = { it.id }) { bill ->
                        DetailRow(bill)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(expenseTotal: Double, incomeTotal: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${String.format("%.2f", expenseTotal)}", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("收入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${String.format("%.2f", incomeTotal)}", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = IncomeGreen)
        }
    }
}

@Composable
private fun CategoryHeader(categoryName: String, subtotal: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(categoryName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text("¥${String.format("%.2f", subtotal)}", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun DetailRow(bill: Bill) {
    val isExpense = bill.billType == "EXPENSE"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = bill.remark ?: (bill.subCategoryName ?: bill.categoryName),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = (if (isExpense) "-" else "+") + String.format("%.2f", bill.amount),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
        )
    }
}
