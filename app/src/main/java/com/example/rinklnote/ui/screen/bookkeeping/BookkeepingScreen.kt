package com.example.rinklnote.ui.screen.bookkeeping

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.component.BillCard
import com.example.rinklnote.ui.component.HeatmapBox
import com.example.rinklnote.ui.component.MonthHeatmap
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.ui.viewmodel.DayPart
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.toDayOfWeek
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookkeepingScreen(
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit,
    viewModel: BookkeepingViewModel
) {
    val horizonalPadding = 10.dp

    val state by viewModel.state.collectAsStateWithLifecycle()

    val isCurrentMonth = state.selectedMonthOffset == 0
    val monthLabel = remember(state.selectedMonthOffset) {
        val d = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong())
        "${d.year}年${d.monthValue}月"
    }

    // 顶部横幅背景：按当前时段对应四张图（数据由 ViewModel 的 state.dayPart 给出）。
    val headerBg = when (state.dayPart) {
        DayPart.MORNING -> R.drawable.morning
        DayPart.DAY -> R.drawable.moon
        DayPart.EVENING -> R.drawable.drop
        DayPart.NIGHT -> R.drawable.night
    }

    // Interaction state
    var revealedBillId by remember { mutableStateOf<Long?>(null) }
    var menuBill by remember { mutableStateOf<Bill?>(null) }
    var deleteTarget by remember { mutableStateOf<Bill?>(null) }
    var showMonthDetail by remember { mutableStateOf(false) }

    // Cache grouped bills to avoid recomputation on every recomposition
    val groupedBills = remember(state.bills) { groupBillsByDate(state.bills) }
    val heatmap = remember(state.bills, state.selectedMonthOffset) {
        computeMonthHeatmap(state.bills, state.selectedMonthOffset)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.onEvent(BookkeepingEvent.PullRefresh) },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "topbar") {
                TopBar(
                    monthLabel = monthLabel,
                    headerBg = headerBg,
                    offset = state.selectedMonthOffset,
                    onPrev = { viewModel.selectMonth(state.selectedMonthOffset - 1) },
                    onNext = { viewModel.selectMonth(state.selectedMonthOffset + 1) },
                    onBackToNow = { viewModel.selectMonth(0) },
                    onOpenDrawer = onOpenDrawer,
                    onFinanceClick = onFinanceClick,
                    onMoreClick = onMoreClick,
                    onAiClick = onAiClick
                )
            }

            item(key = "spacer-0") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            item {
                // AI 总结仅对「当月」展示；切换到其它月份时关闭总结栏（只留合计行）。
                SummaryBar(
                    totalExpense = state.totalExpense,
                    totalIncome = state.totalIncome,
                    currentMonth = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong()).monthValue,
                    aiSummary = if (isCurrentMonth) state.aiSummary else null,
                    aiSummaryLoading = if (isCurrentMonth) state.aiSummaryLoading else false,
                )
            }

            item(key = "spacer-1") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            item(key = "chart") {
                HeatmapBox(
                    heatmap = heatmap,
                    onDetailClick = { showMonthDetail = true }
                )
            }
            item(key = "spacer-2") { Spacer(modifier = Modifier.height(horizonalPadding)) }


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
                    Spacer(modifier = Modifier.height(horizonalPadding))
                }
            }
            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(100.dp)) }
        }
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
            Icon(painter = painterResource(R.drawable.ic_add_bill),
                contentDescription = "增加账单",
                tint = Color.Unspecified
                )
        }

        // Edit overlay — fullscreen, covers everything while editing；用上滑进入（与快加键盘一致）
        AnimatedVisibility(
            visible = state.editingBill != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
        ) {
            state.editingBill?.let { bill ->
                BillEditOverlay(
                    bill = bill,
                    expenseCategories = state.expenseCategories,
                    incomeCategories = state.incomeCategories,
                    accounts = state.accounts,
                    onCancel = { viewModel.onEvent(BookkeepingEvent.CancelEdit) },
                    onConfirm = { newBill -> viewModel.onEvent(BookkeepingEvent.ConfirmEdit(newBill)) },
                    onLoadSubCategories = viewModel::subCategories
                )
            }
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
            month = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong()).withDayOfMonth(1),
            onDismiss = { showMonthDetail = false }
        )
    }
}

@Composable
private fun TopBar(
    monthLabel: String,
    headerBg: Int,
    offset: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit,
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.TopCenter

    ) {
        // 时段横幅背景：四张图按当前时段切换，铺满标题栏（含状态栏后方），ContentScale.Crop 裁切铺满
        Image(
            painter = painterResource(headerBg),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        // 内容层：状态栏避让 + 内容内边距，置于背景图之上，不影响图的满铺
        Box(
            modifier = Modifier
                .matchParentSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
        // 更多 → 我的页；金融 → 资产页；登记 → 记账抽屉
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_menu),
                contentDescription = "更多",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onMoreClick),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_ai),
                contentDescription = "AI 记账",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onAiClick),
                tint = Color.White
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ ",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .pointerInput(Unit) { detectTapGestures { onPrev() } }
            )
            Text(
                text = monthLabel,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { onBackToNow() } }
            )
            Text(
                " ›",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (offset < 0) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .pointerInput(offset) { detectTapGestures { if (offset < 0) onNext() } }
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_finance),
                contentDescription = "金融",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onFinanceClick),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_register),
                contentDescription = "登记",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onOpenDrawer),
                tint = Color.White
            )
        }
        } // 内容层 Box
    }
}

private fun groupBillsByDate(bills: List<Bill>): Map<Long, List<Bill>> {
    return bills.groupBy { it.date }.toList()
        .sortedByDescending { it.first }
        .associate { it.first to it.second }
}

/** 月度每日支出热力图数据：整月每天的支出合计（周一起始布局、首日 weekday、当月天数由 HeatmapBox 用到）。 */
private fun computeMonthHeatmap(bills: List<Bill>, offset: Int): MonthHeatmap {
    val zone = bookkeepingZone()
    val today = LocalDate.now(zone)
    val firstDay = today.plusMonths(offset.toLong()).withDayOfMonth(1)
    val lastDay = firstDay.plusMonths(1).minusDays(1)
    val dailyExpense = HashMap<Int, Float>()
    bills.forEach { bill ->
        if (bill.billType == "EXPENSE") {
            val d = Instant.ofEpochMilli(bill.date).atZone(zone).toLocalDate()
            if (!d.isBefore(firstDay) && !d.isAfter(lastDay)) {
                dailyExpense.merge(d.dayOfMonth, bill.amount.toFloat(), Float::plus)
            }
        }
    }
    return MonthHeatmap(
        year = firstDay.year,
        monthValue = firstDay.monthValue,
        firstWeekday = firstDay.dayOfWeek.value,
        daysInMonth = lastDay.dayOfMonth,
        dailyExpense = dailyExpense
    )
}
