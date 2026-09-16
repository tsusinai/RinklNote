package com.example.rinklnote.ui.screen.challenge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.data.db.entity.ChallengeType
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.DayKind
import com.example.rinklnote.domain.monthEndExclusive
import com.example.rinklnote.domain.monthStartOf
import com.example.rinklnote.domain.toDayStartEpoch
import com.example.rinklnote.domain.weekStartOf
import com.example.rinklnote.ui.component.BillRowContent
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.LocalDate

/**
 * 挑战竞技场（路由 `challenge-arena`，hub「🎮挑战竞技场」卡进入）：
 * 三张挑战大字报卡（无消费日 / 连续记账 / 每周预算）——血条、剩余天数、押注目标，
 * 整卡点击进入 [ChallengeDetailScreen] 单独详情页（进度日历 + 相关真实账单）。
 * 数据全部由 [ChallengeViewModel]（bills 实时派生）供给，本页零写副作用。
 */
@Composable
fun ChallengeArenaScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as RinklNoteApp
    val viewModel: ChallengeViewModel = viewModel(
        factory = ChallengeViewModel.Factory(
            app.database.billDao(),
            app.challengeRepository,
            app.budgetRepository
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (listScrolled) 1f else 0f,
        label = "arenaScrim"
    )
    val pairs = rememberPosterPairs()

    Box(modifier = Modifier.fillMaxSize()) {
        DefaultHazeBackground(hazeState = hazeState)

        if (!state.isLoading && state.hasAnyBill) {
            val cards = deriveArenaCards(state)
            // 三张挑战券各配一对撞色：无消费=薄荷绿、连续记账=番茄红（火）、周预算=电光蓝。
            val cardPairs = listOf(pairs.mint, pairs.tomato, pairs.electric)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

                item(key = "arena-title") {
                    Text(
                        text = "三张挑战券，任你押注",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(cards.size) { index ->
                    ArenaCardItem(
                        card = cards[index],
                        pair = cardPairs[index],
                        order = index + 1,
                        onClick = { onOpenDetail(cards[index].type) }
                    )
                }

                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        PosterTopBar(
            title = "挑战竞技场",
            onBack = onBack,
            scrimAlpha = topBarScrimAlpha
        )
    }
}

/** 竞技场单张挑战大字报：状态贴纸 + 进度行 + 血条 + 押注/剩余贴纸。 */
@Composable
private fun ArenaCardItem(
    card: ArenaChallengeCard,
    pair: PosterPair,
    order: Int,
    onClick: () -> Unit,
) {
    PosterCard(
        pair = pair,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .posterBounceEnter(order)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = card.emoji, fontSize = 30.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = pair.ink
                )
                Text(
                    text = card.progressLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = pair.ink.copy(alpha = 0.8f)
                )
            }
            ArenaStatusChip(card = card)
        }
        Spacer(modifier = Modifier.height(10.dp))
        // 血条：未开局置灰（无目标可押），进度按挑战口径逐格填充。
        BloodBar(
            fraction = if (card.notStarted) 0f else card.fraction,
            fillColor = if (card.notStarted) Color(0xFF9E9E9E) else Color.Black,
            trackColor = pair.ink.copy(alpha = 0.18f),
            segmentCount = 20
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.goalLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = pair.ink.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (card.remainingDays > 0) {
                StickerChip(text = "剩 ${card.remainingDays} 天", rotate = 2f)
            }
        }
    }
}

/** 竞技场状态贴纸：进行中 / 已达成 / 已错过 / 未开局（已达成给金色奖贴）。 */
@Composable
private fun ArenaStatusChip(card: ArenaChallengeCard) {
    val text = when {
        card.notStarted -> "未开局"
        card.achieved -> "已达成"
        card.missed -> "已错过"
        else -> "进行中"
    }
    StickerChip(
        text = text,
        background = if (card.achieved) PosterLemon else Color.White,
        rotate = -3f
    )
}

/**
 * 单挑战详情页（路由 `challenge-detail/{type}`，竞技场卡点击进入）。
 *
 * 结构：挑战大字报 hero（大号当前值 / 血条 / 押注目标 / 剩余天数）→ 该挑战的进度区
 * （无消费日=月历打卡墙、连续记账=档位押注、周预算=7 日火力小柱状 + 上限键盘）→
 * 「相关真实账单」小节（该挑战周期内的真实账单，把省钱行为和消费记录对上账，行点击跳编辑）。
 */
@Composable
fun ChallengeDetailScreen(
    type: String,
    onBack: () -> Unit,
    onEditBill: (Long) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as RinklNoteApp
    val viewModel: ChallengeViewModel = viewModel(
        factory = ChallengeViewModel.Factory(
            app.database.billDao(),
            app.challengeRepository,
            app.budgetRepository
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 周预算上限键盘（自旧挑战卡迁移）：呼出式，确认后经事件落库。
    var weeklyInput by remember { mutableStateOf<String?>(null) }
    var showKeypad by remember { mutableStateOf(false) }
    val parsedWeekly = weeklyInput?.let { Money.parseMinor(it) }

    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val pairs = rememberPosterPairs()

    // 该挑战周期窗口（业务时区）：详情页「相关真实账单」的数据边界。
    val today = LocalDate.now(bookkeepingZone())
    val periodStart: Long = when (type) {
        ChallengeType.WEEKLY_BUDGET -> weekStartOf(today).toDayStartEpoch()
        ChallengeType.BOOKKEEPING_STREAK ->
            state.streakChallenge?.periodStart ?: monthStartOf(today).toDayStartEpoch()
        else -> monthStartOf(today).toDayStartEpoch()
    }
    val periodEnd: Long = when (type) {
        ChallengeType.WEEKLY_BUDGET -> weekStartOf(today).plusWeeks(1).toDayStartEpoch()
        ChallengeType.BOOKKEEPING_STREAK -> today.plusDays(1).toDayStartEpoch()
        else -> monthEndExclusive(today).toDayStartEpoch()
    }
    // 周期内的真实账单（Room Flow，删账单后列表实时回落）。
    val bills by app.database.billDao().observeByMonth(periodStart, periodEnd)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Box(modifier = Modifier.fillMaxSize()) {
        DefaultHazeBackground(hazeState = hazeState)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

            item(key = "hero") {
                DetailHero(type = type, state = state, pairs = pairs)
            }

            item(key = "progress") {
                DetailProgressZone(
                    type = type,
                    state = state,
                    pairs = pairs,
                    bills = bills,
                    onEvent = viewModel::onEvent,
                    onEditGoal = {
                        weeklyInput = ""
                        showKeypad = true
                    }
                )
            }

            item(key = "bills") {
                RelatedBillsCard(
                    type = type,
                    bills = bills,
                    pair = pairs.paper,
                    onEditBill = onEditBill
                )
            }

            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(96.dp)) }
        }

        PosterTopBar(title = detailTitle(type), onBack = onBack)

        // 周预算键盘（呼出式，对齐旧挑战卡交互）。
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
                        cur == "0" -> digit
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

/** 详情页标题（全中文）。 */
private fun detailTitle(type: String): String = when (type) {
    ChallengeType.NO_SPEND_DAY -> "无消费日挑战"
    ChallengeType.BOOKKEEPING_STREAK -> "连续记账挑战"
    ChallengeType.WEEKLY_BUDGET -> "每周预算挑战"
    else -> "挑战详情"
}

/** 详情 hero：大号当前值 + 血条 + 押注目标与剩余贴纸（按挑战类型取数与撞色）。 */
@Composable
private fun DetailHero(type: String, state: ChallengeState, pairs: PosterPairs) {
    val card = deriveArenaCards(state).firstOrNull { it.type == type } ?: return
    val pair = when (type) {
        ChallengeType.WEEKLY_BUDGET -> pairs.electric
        ChallengeType.BOOKKEEPING_STREAK -> pairs.tomato
        else -> pairs.mint
    }
    // 大数字主角：无消费日=已打卡天数；连续记账=连击天数；周预算=本周已花。
    val headline = when (type) {
        ChallengeType.NO_SPEND_DAY -> "${state.monthNoSpendDays} 天"
        ChallengeType.BOOKKEEPING_STREAK -> "${state.streakCurrent} 天"
        else -> Money.format(state.weekExpenseMinor)
    }
    PosterCard(pair = pair, modifier = Modifier.fillMaxWidth().posterBounceEnter(0)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detailTitle(type),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = pair.ink.copy(alpha = 0.75f)
                )
                HugeNumberText(
                    text = headline,
                    ink = pair.ink,
                    fontSize = 44,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            ArenaStatusChip(card = card)
        }
        Spacer(modifier = Modifier.height(10.dp))
        BloodBar(
            fraction = if (card.notStarted) 0f else card.fraction,
            fillColor = if (card.notStarted) Color(0xFF9E9E9E) else Color.Black,
            trackColor = pair.ink.copy(alpha = 0.18f),
            segmentCount = 24
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.progressLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = pair.ink,
                modifier = Modifier.weight(1f)
            )
            if (card.remainingDays > 0) {
                StickerChip(text = "剩 ${card.remainingDays} 天", rotate = 2f)
            }
        }
    }
}

/** 详情进度区：按挑战类型切换（打卡墙 / 档位押注 / 周火力小柱状 + 上限入口）。 */
@Composable
private fun DetailProgressZone(
    type: String,
    state: ChallengeState,
    pairs: PosterPairs,
    bills: List<Bill>,
    onEvent: (ChallengeEvent) -> Unit,
    onEditGoal: () -> Unit,
) {
    when (type) {
        ChallengeType.NO_SPEND_DAY -> NoSpendProgress(state, pairs, onEvent)
        ChallengeType.BOOKKEEPING_STREAK -> StreakProgress(state, pairs, onEvent)
        ChallengeType.WEEKLY_BUDGET -> WeeklyProgress(state, bills, pairs, onEditGoal)
    }
}

/** 无消费日进度区：月历打卡墙 + 图例 + 目标步进（±，事件落库）。 */
@Composable
private fun NoSpendProgress(state: ChallengeState, pairs: PosterPairs, onEvent: (ChallengeEvent) -> Unit) {
    PosterCard(pair = pairs.paper, modifier = Modifier.fillMaxWidth().posterBounceEnter(1)) {
        Text(
            text = "本月打卡墙",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            color = pairs.paper.ink
        )
        Spacer(modifier = Modifier.height(10.dp))
        PunchWall(days = state.punchDays, firstWeekday = state.punchFirstWeekday)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(color = LocalRinklColors.current.incomeColor, label = "无消费")
            LegendDot(color = LocalRinklColors.current.themeColor, label = "有支出")
            LegendDot(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), label = "未记账")
        }
        Spacer(modifier = Modifier.height(12.dp))
        val goal = state.noSpendChallenge?.goal ?: ChallengeViewModel.NO_SPEND_GOAL_DEFAULT
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "押注 $goal 天",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = pairs.paper.ink
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterStepButton(symbol = "−", pair = pairs.lemon) {
                    onEvent(ChallengeEvent.AdjustNoSpendGoal(-1))
                }
                PosterStepButton(symbol = "+", pair = pairs.lemon) {
                    onEvent(ChallengeEvent.AdjustNoSpendGoal(1))
                }
            }
        }
        Text(
            text = "记一笔（哪怕只记收入）零花钱日就能打卡",
            fontSize = 11.sp,
            color = pairs.paper.ink.copy(alpha = 0.6f)
        )
    }
}

/** 连续记账进度区：档位押注按钮（未开局=开始押注；进行中=换档；已错过的行重新开局=新承诺日）。 */
@Composable
private fun StreakProgress(state: ChallengeState, pairs: PosterPairs, onEvent: (ChallengeEvent) -> Unit) {
    val row = state.streakChallenge
    val active = row != null && row.status == ChallengeStatus.ACTIVE && row.goal > 0
    PosterCard(pair = pairs.paper, modifier = Modifier.fillMaxWidth().posterBounceEnter(1)) {
        Text(
            text = if (active) "换押注档位" else "选档开押",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            color = pairs.paper.ink
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "从今天连续记满就达成（只记收入也算），断一天就出局",
            fontSize = 11.sp,
            color = pairs.paper.ink.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChallengeViewModel.STREAK_TIERS.forEach { tier ->
                val picked = active && row?.goal == tier
                StickerButton(
                    text = if (picked) "✓ ${tier}天" else "${tier}天",
                    pair = if (picked) pairs.lemon else pairs.paper,
                    onClick = {
                        if (row == null || row.status != ChallengeStatus.ACTIVE) {
                            onEvent(ChallengeEvent.StartStreakChallenge(tier))
                        } else {
                            onEvent(ChallengeEvent.AdjustStreakGoal(tier))
                        }
                    }
                )
            }
        }
    }
}

/** 周预算进度区：本周 7 日火力小柱状（真实账单派生）+ 上限设置入口。 */
@Composable
private fun WeeklyProgress(
    state: ChallengeState,
    bills: List<Bill>,
    pairs: PosterPairs,
    onEditGoal: () -> Unit,
) {
    // 本周 7 日（周一起）支出合计（整数分，全部来自真实账单）。
    val weekStart = weekStartOf(LocalDate.now(bookkeepingZone()))
    val dayTotals = remember(bills, weekStart) {
        (0 until 7).map { offset ->
            val day = weekStart.plusDays(offset.toLong())
            bills.filter {
                it.billType == BillType.EXPENSE &&
                    Instant.ofEpochMilli(it.date).atZone(bookkeepingZone()).toLocalDate() == day
            }.sumOf { it.amountMinor }
        }
    }
    val maxDay = dayTotals.maxOrNull() ?: 0L
    val goal = state.weeklyChallenge?.goal ?: 0L

    PosterCard(pair = pairs.paper, modifier = Modifier.fillMaxWidth().posterBounceEnter(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本周 7 日火力",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = pairs.paper.ink,
                modifier = Modifier.weight(1f)
            )
            StickerButton(
                text = if (goal > 0) "调整上限" else "设置上限",
                pair = pairs.lemon,
                onClick = onEditGoal
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            dayTotals.forEachIndexed { index, amount ->
                val fraction = if (maxDay > 0) amount.toFloat() / maxDay else 0f
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((16 + 56 * fraction).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "一二三四五六日"[index].toString(),
                        fontSize = 11.sp,
                        color = pairs.paper.ink.copy(alpha = 0.6f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (goal > 0) {
                "整周不超 ${Money.format(goal)} 就赢，单日烧穿会标红提醒"
            } else {
                "还没装本周上限：默认按月预算 ÷ 天数折算，也可手输"
            },
            fontSize = 11.sp,
            color = pairs.paper.ink.copy(alpha = 0.6f)
        )
    }
}

/**
 * 相关真实账单小节：该挑战周期内的真实账单（纸片海报卡），把省钱行为和消费记录对上账。
 * 无消费日的收入笔也在列——正是这些「零支出有记账」的日子撑起了打卡。
 */
@Composable
private fun RelatedBillsCard(
    type: String,
    bills: List<Bill>,
    pair: PosterPair,
    onEditBill: (Long) -> Unit,
) {
    val caption = when (type) {
        ChallengeType.NO_SPEND_DAY -> "当月真实账单：无消费日靠这些记录撑腰"
        ChallengeType.BOOKKEEPING_STREAK -> "承诺期内的真实账单：一天一笔就算活着"
        else -> "本周真实账单：每一笔都算在上限里"
    }
    PosterCard(pair = pair, modifier = Modifier.fillMaxWidth().posterBounceEnter(2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "相关真实账单",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = pair.ink,
                modifier = Modifier.weight(1f)
            )
            StickerChip(text = "共 ${bills.size} 笔", rotate = -2f)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = caption,
            fontSize = 11.sp,
            color = pair.ink.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (bills.isEmpty()) {
            Text(
                text = "这段周期还没有账单",
                fontSize = 13.sp,
                color = pair.ink.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            // 详情页整页是 LazyColumn：这里用普通 Column 平铺（最多 30 笔，防长列表卡顿）。
            bills.take(30).forEachIndexed { index, bill ->
                if (index > 0) RinklDivider(endInset = 6.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditBill(bill.id) }
                ) {
                    BillRowContent(
                        categoryName = bill.subCategoryName ?: bill.categoryName,
                        amountMinor = bill.amountMinor,
                        billType = bill.billType.value,
                        remark = bill.remark
                    )
                }
            }
            if (bills.size > 30) {
                Text(
                    text = "只展示最近 30 笔，共 ${bills.size} 笔",
                    fontSize = 11.sp,
                    color = pair.ink.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 打卡墙（自旧 ChallengeScreen 迁移：周一起始、ISO 补位、今天主色描边、三态着色）
// ---------------------------------------------------------------------------

/** 当月打卡墙三态着色（收入令牌=无消费 / 主题色=有支出 / 灰=未记账，今天主色描边）。 */
@Composable
private fun PunchWall(days: List<PunchDay>, firstWeekday: Int) {
    val noSpendColor = LocalRinklColors.current.incomeColor
    val spendColor = LocalRinklColors.current.themeColor
    val cellShape = RoundedCornerShape(6.dp)

    Column {
        // 星期表头（周一起，与热力图同款）。
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
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

/** 图例小圆点 + 文案（打卡墙用）。 */
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
