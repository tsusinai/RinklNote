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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.toDayOfWeek
import java.time.Instant
import java.time.LocalDate

@Composable
fun BookkeepingScreen(
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit,
    viewModel: BookkeepingViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val monthLabel = remember(state.selectedMonthOffset) {
        val d = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong())
        "${d.year}年${d.monthValue}月"
    }

    // Interaction state
    var revealedBillId by remember { mutableStateOf<Long?>(null) }
    var menuBill by remember { mutableStateOf<Bill?>(null) }
    var deleteTarget by remember { mutableStateOf<Bill?>(null) }
    var showMonthDetail by remember { mutableStateOf(false) }
    var showMonthNav by remember { mutableStateOf(false) }

    // Cache grouped bills to avoid recomputation on every recomposition
    val groupedBills = remember(state.bills) { groupBillsByDate(state.bills) }
    val (chartData, chartLabels) = remember(state.bills, state.selectedMonthOffset) {
        computeMonthChartData(state.bills, state.selectedMonthOffset)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "topbar") {
                TopBar(
                    monthLabel = monthLabel,
                    onMonthClick = { showMonthNav = !showMonthNav },
                    onOpenDrawer = onOpenDrawer,
                    onFinanceClick = onFinanceClick,
                    onMoreClick = onMoreClick,
                    onAiClick = onAiClick
                )
            }
            if (showMonthNav) {
                item(key = "monthnav") {
                    MonthNavigator(
                        offset = state.selectedMonthOffset,
                        onPrev = { viewModel.selectMonth(state.selectedMonthOffset - 1) },
                        onNext = { viewModel.selectMonth(state.selectedMonthOffset + 1) },
                        onBackToNow = { viewModel.selectMonth(0) }
                    )
                }
            }
            item(key = "chart") {
                Spacer(modifier = Modifier.height(24.dp))
                ChartBox(
                    expenseData = chartData,
                    totalExpense = state.totalExpense,
                    totalIncome = state.totalIncome,
                    labels = chartLabels,
                    currentMonth = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong()).monthValue,
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

        // Month detail overlay — shows the currently selected month's summary
        MonthDetailOverlay(
            visible = showMonthDetail,
            monthLabel = monthLabel,
            bills = state.bills,
            onDismiss = { showMonthDetail = false }
        )
    }
}

@Composable
private fun TopBar(
    monthLabel: String,
    onMonthClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 更多 → 我的页；金融 → 资产页；登记 → 记账抽屉（对应原 Pixso 三图标）
        Icon(
            painter = painterResource(R.drawable.ic_more_menu),
            contentDescription = "更多",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(30.dp)
                .clickable(onClick = onMoreClick),
            tint = Color.Unspecified
        )
        Text(
            text = monthLabel,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(onClick = onMonthClick)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_finance),
                contentDescription = "金融",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onFinanceClick),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_register),
                contentDescription = "登记",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onOpenDrawer),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_ai),
                contentDescription = "AI 记账",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onAiClick),
                tint = Color.Unspecified
            )
        }
    }
}

@Composable
private fun MonthNavigator(
    offset: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit
) {
    val d = LocalDate.now().plusMonths(offset.toLong())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPrev) {
            Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = "${d.year}年${d.monthValue}月",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = onNext, enabled = offset < 0) {
            Text(
                "›",
                fontSize = 26.sp,
                color = if (offset < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (offset != 0) {
            TextButton(onClick = onBackToNow) {
                Text("回本月", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun groupBillsByDate(bills: List<Bill>): Map<Long, List<Bill>> {
    return bills.groupBy { it.date }.toList()
        .sortedByDescending { it.first }
        .associate { it.first to it.second }
}

/** 「最近几天」趋势：只展示所选月份末尾 10 天（当月截到今日；历史月为整月末 10 天），不下探到月初之前。 */
private fun computeMonthChartData(bills: List<Bill>, offset: Int): Pair<List<Float>, List<String>> {
    val zone = bookkeepingZone()
    val firstDay = LocalDate.now(zone).plusMonths(offset.toLong()).withDayOfMonth(1)
    val lastDay = if (offset == 0) LocalDate.now() else firstDay.plusMonths(1).minusDays(1)
    // 从 lastDay 往前推 10 天的窗口，若触到月初则截断到月初，避免出现跨月空点
    val windowStart = lastDay.minusDays(9L).let { if (it.isBefore(firstDay)) firstDay else it }
    val days = (java.time.temporal.ChronoUnit.DAYS.between(windowStart, lastDay).toInt()) + 1
    val data = List(days) { windowStart.plusDays(it.toLong()) }
    val values = data.map { d ->
        bills.filter { bill ->
            bill.billType == "EXPENSE" &&
                Instant.ofEpochMilli(bill.date).atZone(zone).toLocalDate() == d
        }.sumOf { it.amount }.toFloat()
    }
    val labels = data.map { "${it.monthValue}.${it.dayOfMonth}" }
    return values to labels
}
