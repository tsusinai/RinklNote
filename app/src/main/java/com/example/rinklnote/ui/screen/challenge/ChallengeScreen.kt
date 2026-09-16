package com.example.rinklnote.ui.screen.challenge

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState

/**
 * 省钱挑战「大字报乐园」hub（路由 `challenges`，记账页「更多」抽屉进入）。
 *
 * 范围红线：只重做挑战区内部——大字报风格是挑战区**局部样式**（硬阴影贴纸卡 / 撞色 /
 * 超大数字，见 ChallengePosterStyle），四个底部 tab、记账页、资产、我的、全局 RinklColors
 * 令牌全部不动；日常记账=安静工具，挑战区=贴满海报的游乐场，刻意反差。
 *
 * 结构（hub 只做导航，不堆数据）：
 * - 顶部大字报：超大「本月已省」（口径 = 挑战节省 = 本月无消费日 × 有消费日日均，
 *   由 bills 经 ChallengeEngine 口径实时派生）+ 副行「本月结余」+ 连击火焰徽章 + 小盘吉祥物探头；
 * - 五张硬阴影贴纸卡：🎮挑战竞技场 / 🏆成就徽章馆 / 🔮结余预测器 / 💌预算信封 / 🎨主题换装间。
 *
 * 页面自包含：签名契约在 [onBack] 之外全部带默认空实现（导航层按需接线，缺省不崩）；
 * 毛玻璃源由本页自建（独立路由不接 nav 层 hazeState）。
 */
@Composable
fun ChallengeScreen(
    onBack: () -> Unit,
    onOpenArena: () -> Unit = {},
    onOpenAchievements: () -> Unit = {},
    onOpenPredictor: () -> Unit = {},
    onOpenEnvelope: () -> Unit = {},
    onOpenThemes: () -> Unit = {},
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
        label = "challengeHubScrim"
    )
    val pairs = rememberPosterPairs()

    Box(modifier = Modifier.fillMaxSize()) {
        DefaultHazeBackground(hazeState = hazeState)

        if (!state.isLoading && !state.hasAnyBill) {
            // 空状态：一笔账都没有 → 引导去记账（挑战与成就都由账单推导，先有账才有得玩）。
            EmptyGuide(onBack = onBack)
        } else if (state.hasAnyBill) {
            val hero = deriveChallengeHero(state)
            val cards = deriveHubCards(state)

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶栏是浮层：首项垫到它下面。
                item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

                item(key = "hero") {
                    HeroPoster(
                        hero = hero,
                        pair = pairs.lemon,
                        order = 0,
                    )
                }

                // 竞技场：主推卡整宽
                item(key = "arena") {
                    HubCardItem(
                        card = cards[0],
                        pair = pairs.electric,
                        order = 1,
                        onClick = onOpenArena,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 徽章馆 + 预测器
                item(key = "mid-row") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HubCardItem(
                            card = cards[1],
                            pair = pairs.tomato,
                            order = 2,
                            onClick = onOpenAchievements,
                            modifier = Modifier.weight(1f)
                        )
                        HubCardItem(
                            card = cards[2],
                            pair = pairs.mint,
                            order = 3,
                            onClick = onOpenPredictor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 信封 + 换装间
                item(key = "bottom-row") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HubCardItem(
                            card = cards[3],
                            pair = pairs.paper,
                            order = 4,
                            onClick = onOpenEnvelope,
                            modifier = Modifier.weight(1f)
                        )
                        HubCardItem(
                            card = cards[4],
                            pair = pairs.lemon,
                            order = 5,
                            onClick = onOpenThemes,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        // 悬浮顶栏：返回 + 居中标题。
        ChallengeTopBar(
            scrimAlpha = topBarScrimAlpha,
            listScrolled = listScrolled,
            onBack = onBack
        )
    }
}

/**
 * hub 顶部大字报：柠檬黄底超大「本月已省」+ 副行结余 + 连击贴纸 + 小盘吉祥物探头。
 * 已省口径（写进注释也写进 UI 小字，避免「感觉被坑」）：
 * 无消费日省下的是「平常一天」的花销 = 本月无消费天数 × 有消费日的日均支出。
 */
@Composable
private fun HeroPoster(hero: ChallengeHeroState, pair: PosterPair, order: Int) {
    PosterCard(pair = pair, modifier = Modifier.fillMaxWidth().posterBounceEnter(order)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本月已省",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = pair.ink.copy(alpha = 0.72f)
                )
                // 超大数字是本页绝对主角：Money.format 负责带不带 ¥（尊重展示偏好）。
                HugeNumberText(text = Money.format(hero.savedMinor), ink = pair.ink, fontSize = 52)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "本月结余 ${heroSurplusText(hero.monthSurplusMinor)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = pair.ink
                )
                Text(
                    text = "${hero.noSpendDays} 个无消费日 × 日均 ${Money.format(hero.spendDayAvgMinor)}",
                    fontSize = 11.sp,
                    color = pair.ink.copy(alpha = 0.6f)
                )
            }
            // 小盘吉祥物从海报顶边探头（贴纸位；资源来自 resource/二次元logo/ 现有位图）。
            MascotPeek()
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 连击火焰徽章：连击 ≥ 3 点亮并持续跳动（低幅度缩放脉冲），未点亮只报天数。
        val pulse = rememberInfiniteTransition(label = "streakFire")
        val fireScale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = if (hero.onFire) 1.12f else 1f,
            animationSpec = infiniteRepeatable(tween(Motion.DurationBreath), RepeatMode.Reverse),
            label = "streakFireScale"
        )
        val streakText = if (hero.streakGoal != null) {
            "🔥 连击 ${hero.streakCurrent}/${hero.streakGoal} 天"
        } else {
            "🔥 连击 ${hero.streakCurrent} 天"
        }
        StickerChip(
            text = streakText,
            modifier = Modifier.graphicsLayer { scaleX = fireScale; scaleY = fireScale },
            rotate = -2f
        )
    }
}

/** hub 功能贴纸卡：emoji + 标题 + 一行副标题 + 右上角角标贴纸，整卡可点（hub 只导航不堆数据）。 */
@Composable
private fun HubCardItem(
    card: HubCardState,
    pair: PosterPair,
    order: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PosterCard(
        pair = pair,
        onClick = onClick,
        modifier = modifier.posterBounceEnter(order)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = card.emoji, fontSize = 26.sp)
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = pair.ink,
                    maxLines = 1
                )
                Text(
                    text = card.subtitle,
                    fontSize = 11.sp,
                    color = pair.ink.copy(alpha = 0.72f),
                    maxLines = 2
                )
            }
        }
        card.badge?.let { badge ->
            Spacer(modifier = Modifier.height(8.dp))
            StickerChip(text = badge, rotate = 2f)
        }
    }
}

/** 挑战 hub 悬浮顶栏：返回 + 居中标题「Rk省钱计划」（沿用工具区顶栏令牌，不海报化）。 */
@Composable
private fun ChallengeTopBar(scrimAlpha: Float, listScrolled: Boolean, onBack: () -> Unit) {
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
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
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

/** 空状态引导：一笔账都没有时替代整页内容（贴纸化：白纸大字报 + 贴纸按钮）。 */
@Composable
private fun EmptyGuide(onBack: () -> Unit) {
    val pairs = rememberPosterPairs()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .posterBounceEnter(0),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PosterCard(pair = pairs.paper) {
                Text(
                    text = "还没一笔账单",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = pairs.paper.ink
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "记下第一笔，省钱挑战与成就就会开始",
                    fontSize = 13.sp,
                    color = pairs.paper.ink.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                StickerButton(
                    text = "去记账 →",
                    pair = pairs.lemon,
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
