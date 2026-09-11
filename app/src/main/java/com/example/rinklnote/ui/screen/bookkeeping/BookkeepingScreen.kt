package com.example.rinklnote.ui.screen.bookkeeping

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.example.rinklnote.ui.component.BillCard
import com.example.rinklnote.ui.component.BillRowContent
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.HeatmapBox
import com.example.rinklnote.ui.component.MonthHeatmap
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.ui.viewmodel.DayPart
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.toDayOfWeek
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun BookkeepingScreen(
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit,
    onMonthDetailClick: () -> Unit,
    onEditBill: (Bill) -> Unit,
    backgroundUri: String?,
    hazeState: HazeState,
    viewModel: BookkeepingViewModel
) {
    val horizonalPadding = 10.dp

    val state by viewModel.monthState.collectAsStateWithLifecycle()

    val isCurrentMonth = state.selectedMonthOffset == 0
    val monthLabel = remember(state.selectedMonthOffset) {
        val d = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong())
        "${d.year}年${d.monthValue}月"
    }

    // 未选照片时仍显示时段横幅（对应四张图）；选了照片则整页铺照片、横幅让位
    val headerBg = when (state.dayPart) {
        DayPart.MORNING -> R.drawable.morning
        DayPart.DAY -> R.drawable.moon
        DayPart.EVENING -> R.drawable.drop
        DayPart.NIGHT -> R.drawable.night
    }

    // Interaction state：长按拖动排序 + 拖入 FAB 删除区（左滑删除与长按菜单已移除）
    val (dragHost, dragHaptics) = rememberBillDragHost()

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 顶栏是浮层：配图背景时把列表首项垫到它下面（不留整块空白）。
    // 高度 = 状态栏避让 + 图标行（30dp 图标 + 上下各 8dp），与 TopBar 的布局保持一致。
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 46.dp
    // 列表滚动后内容会滑到顶栏下方，白色图标需要一层渐隐暗底兜住可读性。
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    // 配图背景：顶栏始终压在照片上，暗底常驻；时段横幅在列表里，滚走后才渐显。
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "topBarScrim"
    )

    // Cache grouped bills to avoid recomputation on every recomposition
    val groupedBills = remember(state.bills) { groupBillsByDate(state.bills) }
    val heatmap = remember(state.bills, state.selectedMonthOffset) {
        computeMonthHeatmap(state.bills, state.selectedMonthOffset)
    }

    // 拖动收尾：松手时按当前状态决定删除（不弹确认框——拖入+松手两段手势即防误触）或重排
    val onDragFinished: () -> Unit = {
        val d = dragHost.dragging
        if (d != null) {
            val dragged = state.bills.firstOrNull { it.id == d.billId }
            if (dragHost.isOverDelete) {
                dragHaptics.confirm()
                dragged?.let { viewModel.onEvent(BookkeepingEvent.DeleteBill(it)) }
            } else if (dragged != null) {
                val dayBills = groupedBills[d.date].orEmpty()
                val others = dayBills.filter { it.id != d.billId }
                val gap = (dragHost.insertionIndex ?: others.size).coerceIn(0, others.size)
                viewModel.onEvent(
                    BookkeepingEvent.ReorderBills(others.toMutableList().apply { add(gap, dragged) })
                )
            }
            dragHost.endDrag()
        }
    }

    // 震动边沿：拖起（重）、进入删除区（重）、插入位变化（轻）
    LaunchedEffect(dragHost.dragging?.billId) {
        if (dragHost.dragging != null) dragHaptics.heavy()
    }
    LaunchedEffect(dragHost.isOverDelete) {
        if (dragHost.isOverDelete) dragHaptics.heavy()
    }
    LaunchedEffect(dragHost.insertionIndex) {
        if (dragHost.insertionIndex != null && !dragHost.isOverDelete) dragHaptics.light()
    }

    // 页面根 Box 在 composition 根下的 Y 偏移（换算 ghost 位置用，通常为 0）
    var screenOriginY by remember { mutableStateOf(0f) }

    Box(modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { screenOriginY = it.boundsInRoot().top }
    ) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满（含底部栏），此处不再叠一层；
        // 无照片时本页自己铺主题渐变，供各卡片 hazeEffect 采样。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.onEvent(BookkeepingEvent.PullRefresh) },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // 时段横幅留在列表里随时间滚走（与顶栏解耦）；配图背景时只垫出顶栏高度，取消上方空白。
            if (backgroundUri == null) {
                item(key = "day-banner") { DayBanner(headerBg = headerBg) }
            } else {
                item(key = "top-bar-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }
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
                    hazeState = hazeState,
                    backgroundUri = backgroundUri
                )
            }

            item(key = "spacer-1") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            item(key = "chart") {
                HeatmapBox(
                    heatmap = heatmap,
                    hazeState = hazeState,
                    onDetailClick = onMonthDetailClick
                )
            }
            item(key = "spacer-2") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            groupedBills.forEach { (date, bills) ->
                item(key = date) {
                    // 缓存该日合计，重组时不重复 sumOf。
                    val totalAmount = remember(date, bills) {
                        bills.sumOf { if (it.billType == BillType.EXPENSE) -it.amount else it.amount }
                    }
                    BillCard(
                        date = date,
                        dayOfWeek = date.toDayOfWeek(),
                        totalAmount = totalAmount,
                        bills = bills,
                        dragHost = dragHost,
                        hazeState = hazeState,
                        backgroundUri = backgroundUri,
                        onEdit = { bill ->
                            viewModel.onEvent(BookkeepingEvent.EditBill(bill))
                            onEditBill(bill)
                        },
                        onDragFinished = onDragFinished
                    )
                    Spacer(modifier = Modifier.height(horizonalPadding))
                }
            }
            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(100.dp)) }
        }
        }

        // 顶栏固定在页面顶部：不随 LazyColumn 滚动，压在照片/横幅/列表内容之上。
        TopBar(
            monthLabel = monthLabel,
            scrimAlpha = topBarScrimAlpha,
            hasBackground = backgroundUri != null,
            listScrolled = listScrolled,
            offset = state.selectedMonthOffset,
            onPrev = { viewModel.selectMonth(state.selectedMonthOffset - 1) },
            onNext = { viewModel.selectMonth(state.selectedMonthOffset + 1) },
            onBackToNow = { viewModel.selectMonth(0) },
            onOpenDrawer = onOpenDrawer,
            onFinanceClick = onFinanceClick,
            onMoreClick = onMoreClick,
            onAiClick = onAiClick
        )

        // FAB：常态 = 加账单；拖动中变删除区（命中时放大 + 红色高亮），松手删除
        val isDragging = dragHost.dragging != null
        val overDelete = dragHost.isOverDelete
        val fabScale by animateFloatAsState(if (overDelete) 1.18f else 1f, label = "fabScale")
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .graphicsLayer { scaleX = fabScale; scaleY = fabScale }
                .rinkShadow(CircleShape)
                .size(51.dp)
                .onGloballyPositioned { dragHost.deleteZone = it.boundsInRoot() }
                .clip(CircleShape)
                .hazeEffect(hazeState, HazeMaterials.thin())
                .clickable(enabled = !isDragging) { onOpenDrawer() },
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = isDragging, label = "fabIcon") { deleting ->
                if (deleting) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "拖到此处删除账单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_bill),
                        contentDescription = "增加账单",
                        tint = Color.Unspecified
                    )
                }
            }
            if (overDelete) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                )
            }
        }

        // 拖动 ghost：被拖行从卡片抽出后，在最顶层跟随手指（略放大 + 投影 + 不透明底保证可读）
        dragHost.dragging?.let { d ->
            val dragged = state.bills.firstOrNull { it.id == d.billId } ?: return@let
            // pointer/bounds 都是 composition 根坐标；换算到本页 Box 内
            val ghostTop = d.pointerY - d.grabOffsetY - screenOriginY
            key(d.billId) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .absoluteOffset(y = with(density) { ghostTop.toDp() })
                        .graphicsLayer {
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                        .zIndex(10f)
                ) {
                    BillRowContent(
                        categoryName = dragged.subCategoryName ?: dragged.categoryName,
                        amount = dragged.amount,
                        billType = dragged.billType.value,
                        remark = dragged.remark,
                        modifier = Modifier
                            .then(applyCardGlass(hazeState, backgroundUri, RoundedCornerShape(12.dp)))
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}


/** 时段横幅：按时段换图的整块头图，作为列表首项随时间滚走，不再包住顶栏。 */
@Composable
private fun DayBanner(headerBg: Int) {
    Image(
        painter = painterResource(headerBg),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun TopBar(
    monthLabel: String,
    scrimAlpha: Float,
    hasBackground: Boolean,
    listScrolled: Boolean,
    offset: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit,
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 文字色三态：有背景→白（靠 scrim/照片衬托）；无背景+顶部→深色（onSurface）压在纯白上；无背景+滚动→浅灰（onSurfaceVariant）
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconColor = textColor
    Box(modifier = modifier.fillMaxWidth()) {
        // 顶部渐隐遮罩：白色图标下的内容（照片/滚动上来的账单）被它压暗，保证可读性。
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.30f * scrimAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        // 内容层：状态栏避让 + 内容内边距，悬浮于照片/渐变/横幅之上
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
                tint = iconColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_ai),
                contentDescription = "AI 记账",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onAiClick),
                tint = iconColor
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
                color = textColor,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .pointerInput(Unit) { detectTapGestures { onPrev() } }
            )
            Text(
                text = monthLabel,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { onBackToNow() } }
            )
            Text(
                " ›",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (offset < 0) textColor else textColor.copy(alpha = 0.35f),
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
                tint = iconColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_register),
                contentDescription = "登记",
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onOpenDrawer),
                tint = iconColor
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
        if (bill.billType == BillType.EXPENSE) {
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
