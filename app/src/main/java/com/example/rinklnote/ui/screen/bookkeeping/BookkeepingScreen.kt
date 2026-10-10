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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
import com.example.rinklnote.ui.component.BillFeedback
import com.example.rinklnote.ui.component.BillRowContent
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.HeatmapBox
import com.example.rinklnote.ui.component.LocalRinklFeedback
import com.example.rinklnote.ui.component.MonthHeatmap
import com.example.rinklnote.ui.component.MoreDrawer
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.ui.viewmodel.DayPart
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.today
import com.example.rinklnote.util.toDayOfWeek
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun BookkeepingScreen(
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    // 保留：抽屉「设置」项回调（跳「我的」页）。顶栏「更多」按钮已改为打开「更多」抽屉。
    onMoreClick: () -> Unit,
    onAiClick: () -> Unit,
    onMonthDetailClick: () -> Unit,
    onEditBill: (Bill) -> Unit,
    backgroundUri: String?,
    hazeState: HazeState,
    viewModel: BookkeepingViewModel,
    // 「更多」抽屉所需：个人信息（未登录时抽屉显示「未登录」+ 引导副文案）
    profileName: String = "",
    profileLoggedIn: Boolean = false,
    // 「更多」抽屉功能项：账单地图（路由 bill-map）/ 导入账单（路由 bill-import）/ 多币种（路由 multi-currency）/ 搜索账单（路由 bill-search）/ Rk省钱计划（路由 challenges）由主会话接线
    onOpenBillMap: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    onOpenMultiCurrency: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenChallenges: () -> Unit = {},
    // 「更多」抽屉功能项：自动记账（路由 auto-capture，2026-10-03 方案 C）由主会话接线
    onOpenAutoCapture: () -> Unit = {},
    // 未登录时「更多」抽屉头部即登录入口（路由 showLogin 浮层由主会话接线）
    onOpenLogin: () -> Unit = {},
    // 日期头点击 → 打开「当天账单」页（路由 day-detail/{dayStart}，主会话接线；
    // 默认空实现防接线前编译断。dayStart = 当日 0 点 epoch millis，业务时区）
    onOpenDay: (Long) -> Unit = {},
    // 拖拽删除撤销（体验审查 P1）：收到删除前快照，接 AppNavigation 的 undoDelete
    // （软删恢复 + 反馈），与 bill-edit 删除路径同一恢复闭包。
    onUndoDelete: (Bill) -> Unit = {},
    // 账单卡片右滑「再来一笔」回调
    onCopyBill: (Bill) -> Unit = {},
    // 待处理自动记账笔数（角标提示）
    pendingCaptureCount: Int = 0
) {
    val horizonalPadding = 10.dp

    val state by viewModel.monthState.collectAsStateWithLifecycle()
    // 结构化反馈（体验审查 P1）：拖删撤销提示经统一 Snackbar 展示（AppNavigation 根部注入）。
    val feedback = LocalRinklFeedback.current

    val isCurrentMonth = state.selectedMonthOffset == 0
    val monthLabel = remember(state.selectedMonthOffset) {
        val d = today().plusMonths(state.selectedMonthOffset.toLong())
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
    val topBarHeight = rememberRinklTopBarHeight()
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

    // 拖动收尾：松手时按当前状态决定删除（不弹确认框——拖入+松手两段手势即防误触）或重排。
    // 删除走既有 VM 事件（软删/余额/推送语义不变），同时给可撤销反馈：快照进 Snackbar，
    // 点「撤销」经 onUndoDelete 恢复（与 bill-edit 删除路径一致）。
    val onDragFinished: () -> Unit = {
        val d = dragHost.dragging
        if (d != null) {
            val dragged = state.bills.firstOrNull { it.id == d.billId }
            if (dragHost.isOverDelete) {
                dragHaptics.confirm()
                dragged?.let { bill ->
                    viewModel.onEvent(BookkeepingEvent.DeleteBill(bill))
                    feedback.show(BillFeedback.DeletedUndoable(snapshot = bill)) {
                        onUndoDelete(bill)
                    }
                }
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

    // 首次拖拽的一次性操作提示：rememberSaveable 会话内只提醒一次（旋转屏幕不重弹）。
    var dragHintShown by rememberSaveable { mutableStateOf(false) }

    // 震动边沿：拖起（重）、进入删除区（重）、插入位变化（轻）；首次拖起顺带给操作提示。
    LaunchedEffect(dragHost.dragging?.billId) {
        if (dragHost.dragging != null) {
            dragHaptics.heavy()
            if (!dragHintShown) {
                dragHintShown = true
                feedback.show(BillFeedback.Plain("松手删除账单，可在提示条中撤销"))
            }
        }
    }
    LaunchedEffect(dragHost.isOverDelete) {
        if (dragHost.isOverDelete) dragHaptics.heavy()
    }
    LaunchedEffect(dragHost.insertionIndex) {
        if (dragHost.insertionIndex != null && !dragHost.isOverDelete) dragHaptics.light()
    }

    // 页面根 Box 在 composition 根下的 Y 偏移（换算 ghost 位置用，通常为 0）
    var screenOriginY by remember { mutableStateOf(0f) }

    // 「更多」左抽屉：顶栏左上角「更多」按钮打开（原直接跳「我的」页，2026-09-14 改为抽屉入口）
    var showMoreDrawer by remember { mutableStateOf(false) }

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
            // contentType：异构 item 分类回收桶，滚动复用只比对同类项（性能任务 2.8，无视觉变化）。
            if (backgroundUri == null) {
                item(key = "day-banner", contentType = "header") { DayBanner(headerBg = headerBg) }
            } else {
                item(key = "top-bar-inset", contentType = "header") { Spacer(modifier = Modifier.height(topBarHeight)) }
            }

            item(key = "spacer-0", contentType = "spacer") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            item(key = "summary", contentType = "summary") {
                // AI 总结仅对「当月」展示；切换到其它月份时关闭总结栏（只留合计行）。
                SummaryBar(
                    totalExpense = state.totalExpense,
                    totalIncome = state.totalIncome,
                    currentMonth = today().plusMonths(state.selectedMonthOffset.toLong()).monthValue,
                    aiSummary = if (isCurrentMonth) state.aiSummary else null,
                    aiSummaryLoading = if (isCurrentMonth) state.aiSummaryLoading else false
                )
            }

            item(key = "spacer-1", contentType = "spacer") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            item(key = "chart", contentType = "chart") {
                HeatmapBox(
                    heatmap = heatmap,
                    onDetailClick = onMonthDetailClick
                )
            }
            item(key = "spacer-2", contentType = "spacer") { Spacer(modifier = Modifier.height(horizonalPadding)) }

            groupedBills.forEach { (date, bills) ->
                item(key = date, contentType = "day-card") {
                    // 缓存该日合计，重组时不重复 sumOf。
                    val totalAmount = remember(date, bills) {
                        bills.sumOf { if (it.billType == BillType.EXPENSE) -it.amountMinor else it.amountMinor }
                    }
                    BillCard(
                        date = date,
                        dayOfWeek = date.toDayOfWeek(),
                        totalAmount = totalAmount,
                        bills = bills,
                        dragHost = dragHost,
                        onEdit = { bill ->
                            viewModel.onEvent(BookkeepingEvent.EditBill(bill))
                            onEditBill(bill)
                        },
                        onDragFinished = onDragFinished,
                        onOpenDay = onOpenDay,
                        onCopyBill = onCopyBill
                    )
                    Spacer(modifier = Modifier.height(horizonalPadding))
                }
            }
            item(key = "bottom-spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(100.dp)) }
        }
        }

        // 顶栏固定在页面顶部：不随 LazyColumn 滚动，压在照片/横幅/列表内容之上。
        TopBar(
            monthLabel = monthLabel,
            scrimAlpha = { topBarScrimAlpha },
            hasBackground = backgroundUri != null,
            listScrolled = listScrolled,
            offset = state.selectedMonthOffset,
            onPrev = { viewModel.selectMonth(state.selectedMonthOffset - 1) },
            onNext = { viewModel.selectMonth(state.selectedMonthOffset + 1) },
            onBackToNow = { viewModel.selectMonth(0) },
            onOpenDrawer = onOpenDrawer,
            onFinanceClick = onFinanceClick,
            onOpenMoreDrawer = { showMoreDrawer = true },
            onAiClick = onAiClick,
            pendingCaptureCount = pendingCaptureCount
        )

        // FAB：常态 = 加账单；拖动中变删除区（命中时放大 + 红色高亮），松手删除
        val isDragging = dragHost.isDragging
        val overDelete = dragHost.isOverDelete
        val fabScale by animateFloatAsState(if (overDelete) 1.18f else 1f, label = "fabScale")
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .graphicsLayer { scaleX = fabScale; scaleY = fabScale }
                .rinkShadow(CircleShape)
                .size(56.dp)
                .onGloballyPositioned { dragHost.deleteZone = it.boundsInRoot() }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .hazeEffect(hazeState, HazeMaterials.thin())
                .clickable(enabled = !isDragging, onClickLabel = "增加账单") { onOpenDrawer() }
                // 拖动中该区变身删除目标区（体验审查 P1 语义）：描述挪到容器上，避免与图标重复播报。
                .then(
                    if (isDragging) Modifier.semantics { contentDescription = "拖到此处删除" } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = isDragging, label = "fabIcon") { deleting ->
                if (deleting) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
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
                        .offset { IntOffset(0, (d.pointerY - d.grabOffsetY - screenOriginY).roundToInt()) }
                        .graphicsLayer {
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                        .zIndex(10f)
                ) {
                    BillRowContent(
                        categoryName = dragged.subCategoryName ?: dragged.categoryName,
                        amountMinor = dragged.amountMinor,
                        billType = dragged.billType.value,
                        remark = dragged.remark,
                        modifier = Modifier
                            .then(applyCardGlass(RoundedCornerShape(15.dp)))
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }

        // 「更多」抽屉：整页最顶层（盖过拖动 ghost）；功能行点击后由组件内部先关抽屉再回调
        MoreDrawer(
            isVisible = showMoreDrawer,
            profileName = profileName,
            profileLoggedIn = profileLoggedIn,
            onDismiss = { showMoreDrawer = false },
            onOpenBillMap = onOpenBillMap,
            onOpenImport = onOpenImport,
            onOpenMultiCurrency = onOpenMultiCurrency,
            onOpenSearch = onOpenSearch,
            onOpenChallenges = onOpenChallenges,
            onOpenAutoCapture = onOpenAutoCapture,
            pendingCaptureCount = pendingCaptureCount,
            onOpenLogin = onOpenLogin,
            onOpenSettings = onMoreClick,
            onOpenAbout = onMoreClick, // 「关于」暂同「设置」跳「我的」页，后续可接独立关于页
            hazeState = hazeState,
            modifier = Modifier.zIndex(20f)
        )
    }
}


/**
 * 时段头图高度（无自选背景时，记账页顶部那张按时段换的横幅）。
 *
 * 取 190dp 的由来：源图是 1920×1081（morning，16:9）与 1920×1280（其余，3:2），
 * `ContentScale.Crop` 会按屏宽裁上下——屏宽 360dp 时 190dp ≈ 1.89:1，
 * 已接近 16:9 原图比例，基本只裁掉 3:2 那些图的一小圈；
 * 150dp（≈2.4:1）裁得偏狠，图意常被切掉（2026-09-11 用户两次上调：150 → 170 → 190）。
 */
private val DayBannerHeight = 190.dp

/** 时段横幅：按时段换图的整块头图，作为列表首项随时间滚走，不再包住顶栏。 */
@Composable
private fun DayBanner(headerBg: Int) {
    Image(
        painter = painterResource(headerBg),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(DayBannerHeight),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun TopBar(
    monthLabel: String,
    scrimAlpha: () -> Float,
    hasBackground: Boolean,
    listScrolled: Boolean,
    offset: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit,
    onOpenDrawer: () -> Unit,
    onFinanceClick: () -> Unit,
    onOpenMoreDrawer: () -> Unit,
    onAiClick: () -> Unit,
    pendingCaptureCount: Int = 0,
    modifier: Modifier = Modifier
) {
    // 文字色三态：有背景→白（靠 scrim/照片衬托）；无背景→自定义主题「顶栏标题色」（默认=字体色），滚动后略淡
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
    val iconColor = textColor
    RinklTopBar(
        scrimAlpha = scrimAlpha,
        horizontalPadding = 16.dp,
        modifier = modifier
    ) {
        // 更多 → 「更多」抽屉（待处理计数>0 时挂红点提示）；AI → AI 记账（金融/登记在右列）
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onOpenMoreDrawer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_menu),
                    contentDescription = if (pendingCaptureCount > 0) "更多，有 $pendingCaptureCount 条待处理自动记账" else "更多",
                    modifier = Modifier.size(30.dp),
                    tint = iconColor
                )
                if (pendingCaptureCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                }
            }
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
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 月份切换箭头（体验审查 P1）：裸 pointerInput 改为 IconButton，触控目标 ≥48dp，
            // 并补「上个月 / 下个月」无障碍描述；箭头字阶与配色保持不变。
            IconButton(
                onClick = onPrev,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "上个月" }
            ) {
                Text(
                    "‹",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
            Text(
                text = monthLabel,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "回到本月"
                ) { onBackToNow() }
            )
            IconButton(
                onClick = onNext,
                enabled = offset < 0,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "下个月" }
            ) {
                Text(
                    "›",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    // 已是当月时箭头置灰（视觉不变）；按钮 disabled + enabled 门控同步语义。
                    color = if (offset < 0) textColor else textColor.copy(alpha = 0.35f)
                )
            }
        }
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
                dailyExpense.merge(d.dayOfMonth, bill.amountMinor.toFloat(), Float::plus)
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
