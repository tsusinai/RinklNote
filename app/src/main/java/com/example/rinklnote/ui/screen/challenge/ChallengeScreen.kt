package com.example.rinklnote.ui.screen.challenge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.R
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.domain.AchievementState
import com.example.rinklnote.domain.DayKind
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.achievementBadgeName
import com.example.rinklnote.ui.component.achievementBadgeRes
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState

/**
 * 省钱挑战页（独立路由 `challenges`，由主会话/E2 接线）。
 *
 * 结构（从上到下）：预测卡（含少买滑杆）→ 三张挑战卡（无消费日卡内嵌当月打卡墙 /
 * 连续记账卡 / 周预算卡）→ 成就墙（3 列 15 枚）→ 主题解锁行（晨曦/薄荷/琥珀）。
 * 视觉对齐计划页：卡片 15dp 圆角 + rinkShadow + 毛玻璃、悬浮顶栏 46dp、字号 ≥12sp、
 * 颜色一律走 LocalRinklColors 令牌（收支色用 EXPENSE/INCOME 槽）。
 *
 * 页面自包含：签名契约固定只有 [onBack]，毛玻璃源由本页自建（独立路由不接 nav 层 hazeState）。
 *
 * @param onBack 返回上一页
 */
@Composable
fun ChallengeScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as RinklNoteApp
    val viewModel: ChallengeViewModel = viewModel(
        factory = ChallengeViewModel.Factory(
            app.database.billDao(),
            app.challengeRepository,
            app.budgetRepository
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 本页自建毛玻璃源（周预算键盘的面板也采样它）。
    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (listScrolled) 1f else 0f,
        label = "challengeTopBarScrim"
    )

    // 周预算键盘：呼出式（对齐预算编辑页），输入中的金额存本地，确认后经事件落库。
    var weeklyInput by remember { mutableStateOf<String?>(null) }
    var showKeypad by remember { mutableStateOf(false) }
    val parsedWeekly = weeklyInput?.let { Money.parseMinor(it) }

    BackHandler {
        if (showKeypad) showKeypad = false else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DefaultHazeBackground(hazeState = hazeState)

        if (!state.isLoading && !state.hasAnyBill) {
            // 空状态：一笔账都没有 → 引导去记账（成就与挑战都由账单推导，先有账才有得挑战）。
            // isLoading 期间先不渲染任何主体，避免「首笔账还没查出来就闪空态」。
            EmptyGuide(onBack = onBack)
        } else if (state.hasAnyBill) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 顶栏是浮层：首项垫到它下面（不留整块空白）。
                item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

                item(key = "forecast") {
                    ForecastCard(
                        state = state,
                        onSelectCategory = { name ->
                            viewModel.onEvent(ChallengeEvent.SelectLessBuyCategory(name))
                        },
                        onAdjustPercent = { percent ->
                            viewModel.onEvent(ChallengeEvent.SetLessBuyPercent(percent))
                        }
                    )
                }

                item(key = "no-spend") {
                    NoSpendCard(
                        state = state,
                        onAdjustGoal = { delta ->
                            viewModel.onEvent(ChallengeEvent.AdjustNoSpendGoal(delta))
                        }
                    )
                }

                item(key = "streak") {
                    StreakCard(
                        state = state,
                        onPickTier = { tier ->
                            val row = state.streakChallenge
                            if (row == null || row.status != ChallengeStatus.ACTIVE) {
                                viewModel.onEvent(ChallengeEvent.StartStreakChallenge(tier))
                            } else {
                                viewModel.onEvent(ChallengeEvent.AdjustStreakGoal(tier))
                            }
                        }
                    )
                }

                item(key = "weekly") {
                    WeeklyCard(
                        state = state,
                        onEditGoal = {
                            weeklyInput = ""
                            showKeypad = true
                        }
                    )
                }

                item(key = "achievements") { AchievementWall(state.achievements) }

                item(key = "themes") { ThemeUnlockCard(state.themeUnlocks) }

                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        // 悬浮顶栏：返回 + 居中标题（对齐分类预算设置页）。
        ChallengeTopBar(
            scrimAlpha = topBarScrimAlpha,
            listScrolled = listScrolled,
            onBack = onBack
        )

        // 键盘呼出时先铺半透明遮罩，点遮罩收回键盘。
        if (showKeypad) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showKeypad = false }
            )
        }

        AnimatedVisibility(
            visible = showKeypad,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
        ) {
            NumericKeypad(
                amount = weeklyInput.orEmpty(),
                billType = "EXPENSE",
                remark = "",
                onDigit = { digit ->
                    val cur = weeklyInput.orEmpty()
                    weeklyInput = when {
                        digit == "." -> if (cur.isEmpty()) "0." else if (cur.contains(".")) cur else cur + digit
                        cur == "0" -> digit // 替换前导零
                        else -> cur + digit
                    }
                },
                onClear = { weeklyInput = "" },
                onBackspace = { weeklyInput = weeklyInput.orEmpty().dropLast(1) },
                onToggleType = {},
                showTypeToggle = false,
                showRemark = false,
                confirmEnabled = parsedWeekly != null,
                onConfirm = {
                    parsedWeekly?.let { amount ->
                        viewModel.onEvent(ChallengeEvent.SetWeeklyBudget(amount))
                        showKeypad = false
                    }
                },
                hazeState = hazeState,
                bottomPadding = 16.dp
            )
        }
    }
}

/** 挑战页悬浮顶栏：返回（AutoMirrored ArrowBack 24dp）+ 居中标题「Rk省钱计划」20sp Medium。 */
@Composable
private fun ChallengeTopBar(
    scrimAlpha: Float,
    listScrolled: Boolean,
    onBack: () -> Unit
) {
    // 文字色两态：无背景照片，未滚动用自定义主题「顶栏标题色」，滚动后略淡。
    val textColor = if (!listScrolled) {
        LocalRinklColors.current.topBarTitleColor
    } else {
        LocalRinklColors.current.topBarTitleColorScrolled
    }
    RinklTopBar(
        scrimAlpha = scrimAlpha,
        horizontalPadding = 8.dp
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "Rk省钱计划",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 空状态引导：一笔账都没有时替代整页内容。 */
@Composable
private fun EmptyGuide(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(15.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .rinkShadow(shape)
                .clip(shape)
                .then(applyCardGlass(shape))
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "还没有一笔账单",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "记下第一笔，省钱挑战与成就就会开始",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "去记账",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * 预测卡：本月已花 / 日均 / 月底支出预测；设了总额预算再给「预计结余」（超支红字）。
 * 内嵌「少买一点」：选当月支出 Top 分类 + 百分比步进（10%~100%），实时试算可省金额。
 */
@Composable
private fun ForecastCard(
    state: ChallengeState,
    onSelectCategory: (String?) -> Unit,
    onAdjustPercent: (Int) -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "本月预测",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "已花 ${Money.format(state.monthExpenseMinor)} · 日均 ${Money.format(state.dailyAverageMinor)}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            Text(
                text = "预计月底支出 ",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = Money.format(state.forecastMinor),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (state.forecastOverBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        state.forecastBalanceMinor?.let { balance ->
            Spacer(modifier = Modifier.height(4.dp))
            if (state.forecastOverBudget) {
                Text(
                    text = "按这个花法预计超支 ${Money.format(-balance)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "预计结余 ${Money.format(balance)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalRinklColors.current.incomeColor
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "少买一点",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.categoryTotals.isEmpty()) {
            Text(
                text = "记几笔支出后，可以在这里试算「少买一点」能省多少",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 候选分类：当月支出 Top（横滑一行），点选后实时重算省钱数。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.categoryTotals.forEach { category ->
                    SelectChip(
                        label = category.categoryName,
                        selected = category.categoryName == state.lessBuyCategory,
                        onClick = {
                            onSelectCategory(
                                if (category.categoryName == state.lessBuyCategory) null else category.categoryName
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepButton(symbol = "−") { onAdjustPercent(state.lessBuyPercent - ChallengeViewModel.LESS_BUY_PERCENT_STEP) }
                Text(
                    text = "少买 ${state.lessBuyPercent}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                StepButton(symbol = "+") { onAdjustPercent(state.lessBuyPercent + ChallengeViewModel.LESS_BUY_PERCENT_STEP) }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (state.lessBuySavingMinor > 0) {
                        "约可省 ${Money.format(state.lessBuySavingMinor)}"
                    } else {
                        "选个分类试试"
                    },
                    fontSize = 12.sp,
                    color = if (state.lessBuySavingMinor > 0) {
                        LocalRinklColors.current.incomeColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** 无消费日挑战卡：当月进度 + 月历打卡墙（绿=无消费 / 主题色=有支出 / 灰=未记账，今天描边）+ 目标步进。 */
@Composable
private fun NoSpendCard(
    state: ChallengeState,
    onAdjustGoal: (Int) -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val goal = state.noSpendChallenge?.goal ?: ChallengeViewModel.NO_SPEND_GOAL_DEFAULT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "无消费日挑战",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ChallengeStatusLabel(status = state.noSpendChallenge?.status)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "本月已达成 ${state.monthNoSpendDays} 天 / 目标 $goal 天",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                if (goal > 0) (state.monthNoSpendDays.toDouble() / goal).toFloat().coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "本月还剩 ${state.noSpendRemainingDays} 天，记一笔（哪怕只记收入）零花钱日就能打卡",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))
        PunchWall(
            days = state.punchDays,
            firstWeekday = state.punchFirstWeekday
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(color = LocalRinklColors.current.incomeColor, label = "无消费")
            LegendDot(color = LocalRinklColors.current.themeColor, label = "有支出")
            LegendDot(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                label = "未记账"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "目标 $goal 天",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepButton(symbol = "−") { onAdjustGoal(-1) }
                StepButton(symbol = "+") { onAdjustGoal(1) }
            }
        }
    }
}

/**
 * 当月打卡墙：骨架复用自 `ui/component/HeatmapBox.kt` 的月历格（周一起始、ISO 补位、
 * 7 列小圆角格、今天主色描边）；着色由「支出强度渐变」改为打卡三态语义：
 * 绿（收入令牌）=无消费日、主题色=有支出、灰=未记账，未来的日子更淡。
 */
@Composable
private fun PunchWall(
    days: List<PunchDay>,
    firstWeekday: Int
) {
    val noSpendColor = LocalRinklColors.current.incomeColor
    val spendColor = LocalRinklColors.current.themeColor
    val cellShape = RoundedCornerShape(6.dp)

    Column {
        // 星期表头（周一起，与热力图同款）。
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        val leadingBlanks = (firstWeekday - 1).coerceIn(0, 6)
        val totalCells = leadingBlanks + days.size
        val rows = (totalCells + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val index = r * 7 + c - leadingBlanks
                    if (index in days.indices) {
                        val day = days[index]
                        val bg = when {
                            day.isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            day.kind == DayKind.NO_SPEND -> noSpendColor.copy(alpha = 0.85f)
                            day.kind == DayKind.SPEND -> spendColor.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                        val filled = !day.isFuture && day.kind != DayKind.NO_RECORD
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.2f)
                                .padding(2.dp)
                                .then(
                                    if (day.isToday) {
                                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, cellShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clip(cellShape)
                                .background(bg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = day.dayOfMonth.toString(),
                                fontSize = 12.sp,
                                color = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.2f)
                        )
                    }
                }
            }
        }
    }
}

/** 连续记账挑战卡：未开始给档位选择（7/14/21/30/50/100），进行中给进度与换档。 */
@Composable
private fun StreakCard(
    state: ChallengeState,
    onPickTier: (Long) -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val row = state.streakChallenge
    val goal = row?.goal ?: 0L
    val active = row != null && row.status == ChallengeStatus.ACTIVE && goal > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "连续记账挑战",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (row != null) {
                ChallengeStatusLabel(status = row.status)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "已连续记账 ${state.streakCurrent} 天",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (active) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (state.streakCurrent.toDouble() / goal).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            val daysToGo = (goal - state.streakCurrent).coerceAtLeast(0)
            Text(
                text = if (daysToGo > 0) "目标 $goal 天 · 还差 $daysToGo 天" else "目标 $goal 天 · 已达成",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "选一个目标开始挑战，从今天连续记满就达成（只记收入也算）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (active) "调整目标档位" else "选择目标开始",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChallengeViewModel.STREAK_TIERS.forEach { tier ->
                SelectChip(
                    label = "${tier}天",
                    selected = active && row?.goal == tier,
                    onClick = { onPickTier(tier) }
                )
            }
        }
    }
}

/** 周预算挑战卡：本周支出 vs 上限进度条；未设上限引导手输（NumericKeypad）。 */
@Composable
private fun WeeklyCard(
    state: ChallengeState,
    onEditGoal: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val goal = state.weeklyChallenge?.goal ?: 0L
    val over = goal > 0 && state.weekExpenseMinor > goal
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "周预算挑战",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.weeklyChallenge != null) {
                    ChallengeStatusLabel(status = state.weeklyChallenge?.status)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = if (goal > 0) "调整上限" else "设置周上限",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onEditGoal)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (goal <= 0) {
            Text(
                text = "本周已花 ${Money.format(state.weekExpenseMinor)}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "还没设本周上限，设一个试试（默认按月预算 ÷ 天数折算，可在计划页设月预算）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(
                progress = {
                    (state.weekExpenseMinor.toDouble() / goal).toFloat().coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "已花 ${Money.format(state.weekExpenseMinor)} / 上限 ${Money.format(goal)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (over) {
                        "已超 ${Money.format(state.weekExpenseMinor - goal)}"
                    } else {
                        "${(state.weekExpenseMinor.toDouble() / goal * 100).toInt()}%"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "本周还剩 ${state.weekRemainingDays} 天",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 成就墙：3 列网格 15 枚。图标资产契约：`R.drawable.badge_<id 下划线化>`（E2 负责 SVG → VectorDrawable）。
 * 锁定态灰度 + 半透明 + 进度 x/y；解锁态原色并带一次弹入庆祝动效。
 */
@Composable
private fun AchievementWall(achievements: List<AchievementState>) {
    val shape = RoundedCornerShape(15.dp)
    val unlockedCount = achievements.count { it.unlocked }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "成就墙",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "已解锁 $unlockedCount / ${achievements.size.coerceAtLeast(1)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        achievements.chunked(3).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { badge ->
                    Box(modifier = Modifier.weight(1f)) {
                        AchievementCell(badge = badge)
                    }
                }
                repeat(3 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AchievementCell(badge: AchievementState) {
    // 解锁徽章一次性弹入（庆祝动效）；锁定态灰度 + 半透明。
    val scale = remember(badge.id) { Animatable(if (badge.unlocked) 0.6f else 1f) }
    LaunchedEffect(badge.id, badge.unlocked) {
        if (badge.unlocked && scale.value < 1f) {
            scale.animateTo(1f, animationSpec = tween(Motion.DurationIndicator, easing = Motion.IndicatorEasing))
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(achievementBadgeRes(badge.id)),
            contentDescription = achievementBadgeName(badge.id),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            alpha = if (badge.unlocked) 1f else 0.45f,
            colorFilter = if (badge.unlocked) {
                null
            } else {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = achievementBadgeName(badge.id),
            fontSize = 12.sp,
            color = if (badge.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = if (badge.unlocked) "已解锁" else "${badge.progressCurrent}/${badge.progressTarget}",
            fontSize = 12.sp,
            color = if (badge.unlocked) {
                LocalRinklColors.current.incomeColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

// 徽章图标 / 中文名映射已抽到 ui/component/AchievementBadgeUi.kt（成就墙与「我的」徽章展示管理共用）。


/** 主题解锁行（晨曦/薄荷/琥珀）：锁图标 + 条件文案；解锁高亮。应用动作在自定义主题页（E2 接线）。 */
@Composable
private fun ThemeUnlockCard(themes: List<ThemeUnlockState>) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "主题解锁",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        themes.forEach { theme ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (theme.unlocked) {
                        LocalRinklColors.current.themeColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = theme.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (theme.unlocked) {
                            LocalRinklColors.current.themeColor
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = theme.condition,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (theme.unlocked) "已解锁" else "未解锁",
                    fontSize = 12.sp,
                    color = if (theme.unlocked) {
                        LocalRinklColors.current.themeColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** 挑战状态角标：已达成（收入绿）/ 已错过（error 红）/ 进行中（主题色）。 */
@Composable
private fun ChallengeStatusLabel(status: String?) {
    val (text, color) = when (status) {
        ChallengeStatus.ACHIEVED -> "已达成" to LocalRinklColors.current.incomeColor
        ChallengeStatus.MISSED -> "已错过" to MaterialTheme.colorScheme.error
        else -> "进行中" to MaterialTheme.colorScheme.primary
    }
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color
    )
}

/** 可选小胶囊（分类 / 档位共用）：选中态主色底，未选中灰底。 */
@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .defaultMinSize(minHeight = 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 步进按钮（±）：44dp 触控目标，无水波纹（对齐键盘按键观感）。 */
@Composable
private fun StepButton(
    symbol: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 图例小圆点 + 文案。 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
