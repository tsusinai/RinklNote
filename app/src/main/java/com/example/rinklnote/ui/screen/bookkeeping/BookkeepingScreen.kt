package com.example.rinklnote.ui.screen.bookkeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.component.BillCard
import com.example.rinklnote.ui.component.ChartBox
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.util.toDayOfWeek
import com.example.rinklnote.util.toHeaderString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun BookkeepingScreen(
    onOpenDrawer: () -> Unit,
    viewModel: BookkeepingViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Cache grouped bills to avoid recomputation on every recomposition
    val groupedBills = remember(state.bills) { groupBillsByDate(state.bills) }
    val (chartData, chartLabels) = remember(state.bills) { computeChartData(state.bills) }

    // Interaction state
    var revealedBillId by remember { mutableStateOf<Long?>(null) }
    var menuBill by remember { mutableStateOf<Bill?>(null) }
    var deleteTarget by remember { mutableStateOf<Bill?>(null) }
    var showMonthDetail by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "topbar") { TopBar(date = state.currentDate) }
            item(key = "chart") {
                Spacer(modifier = Modifier.height(24.dp))
                ChartBox(
                    expenseData = chartData,
                    totalExpense = state.totalExpense,
                    totalIncome = state.totalIncome,
                    labels = chartLabels,
                    onDetailClick = { showMonthDetail = true }
                )
            }
            item(key = "spacer") { Spacer(modifier = Modifier.height(20.dp)) }

            groupedBills.forEach { (date, bills) ->
                item(key = date) {
                    BillCard(
                        date = date,
                        dayOfWeek = date.toDayOfWeek(),
                        totalAmount = bills.sumOf { if (it.billType == "EXPENSE") -it.amount else it.amount },
                        bills = bills,
                        revealedBillId = revealedBillId,
                        menuBill = menuBill,
                        onRevealChange = { revealedBillId = it },
                        onMenuChange = { menuBill = it },
                        onEdit = { bill ->
                            menuBill = null
                            viewModel.onEvent(BookkeepingEvent.EditBill(bill))
                        },
                        onDelete = { bill ->
                            menuBill = null
                            deleteTarget = bill
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            item { Spacer(modifier = Modifier.height(100.dp)) }
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .shadow(4.dp, CircleShape)
                .size(51.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable { onOpenDrawer() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        }

        // Edit overlay — fullscreen, covers everything while editing
        state.editingBill?.let { bill ->
            BillEditOverlay(
                bill = bill,
                expenseCategories = state.expenseCategories,
                incomeCategories = state.incomeCategories,
                accounts = state.accounts,
                onCancel = { viewModel.onEvent(BookkeepingEvent.CancelEdit) },
                onConfirm = { newBill -> viewModel.onEvent(BookkeepingEvent.ConfirmEdit(newBill)) }
            )
        }

        // Delete confirm dialog
        deleteTarget?.let { bill ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除账单") },
                text = { Text("确定删除这笔${(bill.subCategoryName ?: bill.categoryName)}的账单吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        viewModel.onEvent(BookkeepingEvent.DeleteBill(bill))
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                }
            )
        }

        // Month detail overlay
        MonthDetailOverlay(
            visible = showMonthDetail,
            monthBills = state.monthBills,
            onDismiss = { showMonthDetail = false }
        )
    }
}

@Composable
private fun TopBar(date: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_more_menu),
                contentDescription = "更多",
                modifier = Modifier.size(30.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(85.dp))
            Text(
                text = date.toHeaderString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row {
            Icon(
                painter = painterResource(R.drawable.ic_finance),
                contentDescription = "金融",
                modifier = Modifier.size(30.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.padding(start = 8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_register),
                contentDescription = "登记",
                modifier = Modifier.size(30.dp),
                tint = Color.Unspecified
            )
        }
    }
}

private fun groupBillsByDate(bills: List<Bill>): Map<Long, List<Bill>> {
    return bills.groupBy { it.date }.toList()
        .sortedByDescending { it.first }
        .associate { it.first to it.second }
}

private fun computeChartData(bills: List<Bill>): Pair<List<Float>, List<String>> {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val data = List(10) { offset ->
        val targetDate = today.minusDays((9 - offset).toLong())
        bills.filter { bill ->
            val billDate = Instant.ofEpochMilli(bill.date).atZone(zone).toLocalDate()
            billDate == targetDate && bill.billType == "EXPENSE"
        }.sumOf { it.amount }.toFloat()
    }
    val labels = List(10) { offset ->
        val targetDate = today.minusDays((9 - offset).toLong())
        "${targetDate.monthValue}.${targetDate.dayOfMonth}"
    }
    return data to labels
}
