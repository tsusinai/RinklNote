package com.example.rinklnote.ui.screen.challenge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.rinklnote.ui.component.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.example.rinklnote.ui.component.achievementBadgeRes
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import dev.chrisbanes.haze.HazeState

/**
 * 成就徽章馆（路由 `challenge-achievements`，hub「🏆成就徽章馆」卡进入）：
 * 徽章墙按已解锁 / 未解锁陈列（3 列网格），每枚点开显示达成条件 + 由真实账单算出的当前进度。
 * 零存储特性照旧：全部由 bills 实时派生，删账单进度实时回退（特性不是 bug）。
 * 解锁时刻：页面打开期间从锁定 → 解锁的瞬间，全屏贴纸炸开（[StickerBurstOverlay]）。
 */
@Composable
fun AchievementsScreen(onBack: () -> Unit) {
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
        label = "achievementsScrim"
    )
    val pairs = rememberPosterPairs()

    // 解锁炸开：记录上一次的已解锁集合；页面打开期间出现新解锁 → 触发一次全屏贴纸炸开。
    // 首次进入（previous == null）只记录不触发，避免「一打开页面就炸」。
    var previousUnlocked by remember { mutableStateOf<Set<String>?>(null) }
    var burst by remember { mutableStateOf(false) }
    LaunchedEffect(state.achievements) {
        val unlockedIds = state.achievements.filter { it.unlocked }.map { it.id }.toSet()
        val previous = previousUnlocked
        if (previous != null && unlockedIds.any { it !in previous }) {
            burst = true
        }
        previousUnlocked = unlockedIds
    }

    var selected by remember { mutableStateOf<AchievementBadgeState?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        DefaultHazeBackground(hazeState = hazeState)

        if (!state.isLoading && state.hasAnyBill) {
            val badges = deriveAchievementBadges(state)
            val unlocked = badges.filter { it.unlocked }
            val locked = badges.filterNot { it.unlocked }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

                // 徽章馆 hero：柠檬黄大字报，已解锁枚数是绝对主角。
                item(key = "hero") {
                    PosterCard(
                        pair = pairs.lemon,
                        modifier = Modifier.fillMaxWidth().posterBounceEnter(0)
                    ) {
                        Text(
                            text = "徽章点亮进度",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = pairs.lemon.ink.copy(alpha = 0.72f)
                        )
                        HugeNumberText(
                            text = "${unlocked.size} / ${badges.size.coerceAtLeast(1)}",
                            ink = pairs.lemon.ink,
                            fontSize = 48
                        )
                        Text(
                            text = "每一枚都由真实账单算出，删账单会实时回退——真本事才亮得住",
                            fontSize = 11.sp,
                            color = pairs.lemon.ink.copy(alpha = 0.6f)
                        )
                    }
                }

                // 已点亮陈列（3 列网格）。
                item(key = "unlocked-label") { SectionSticker(text = "已点亮 ${unlocked.size}") }
                val unlockedRows = unlocked.chunked(3)
                items(unlockedRows.size) { rowIndex ->
                    BadgeGridRow(rowItems = unlockedRows[rowIndex], onPick = { selected = it })
                }

                // 待点亮陈列（3 列网格，灰度）。
                item(key = "locked-label") { SectionSticker(text = "待点亮 ${locked.size}") }
                val lockedRows = locked.chunked(3)
                items(lockedRows.size) { rowIndex ->
                    BadgeGridRow(rowItems = lockedRows[rowIndex], onPick = { selected = it })
                }

                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        PosterTopBar(title = "成就徽章馆", onBack = onBack, scrimAlpha = topBarScrimAlpha)

        // 解锁时刻全屏贴纸炸开。
        StickerBurstOverlay(trigger = burst, onFinished = { burst = false })

        // 徽章详情弹层：达成条件 + 当前进度（真实账单派生）。
        selected?.let { badge ->
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(badge.name, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(text = "达成条件：${badge.condition}", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (badge.unlocked) {
                                "已点亮 🎉"
                            } else {
                                "当前进度：${badge.progressCurrent} / ${badge.progressTarget}"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (badge.unlocked) {
                                LocalRinklColors.current.incomeColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selected = null }) { Text("知道了") }
                }
            )
        }
    }
}

/** 徽章区小标题贴纸。 */
@Composable
private fun SectionSticker(text: String) {
    StickerChip(text = text, rotate = -2f)
}

/** 一行 3 枚徽章（不足补空位），整格可点开详情。 */
@Composable
private fun BadgeGridRow(rowItems: List<AchievementBadgeState>, onPick: (AchievementBadgeState) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        rowItems.forEach { badge ->
            Box(modifier = Modifier.weight(1f)) {
                BadgeCell(badge = badge, onClick = { onPick(badge) })
            }
        }
        repeat(3 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
    }
}

/**
 * 单枚徽章：图标 + 名称 + 进度。锁定态灰度 + 半透明；解锁态带一次弹入庆祝动效
 * （沿用旧成就墙的 Animatable 弹入，规格走 Motion 令牌）。
 */
@Composable
private fun BadgeCell(badge: AchievementBadgeState, onClick: () -> Unit) {
    // 解锁徽章一次性弹入（庆祝动效）。
    val scale = remember(badge.id) { Animatable(if (badge.unlocked) 0.6f else 1f) }
    LaunchedEffect(badge.id, badge.unlocked) {
        if (badge.unlocked && scale.value < 1f) {
            scale.animateTo(1f, animationSpec = tween(Motion.DurationIndicator, easing = Motion.IndicatorEasing))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(achievementBadgeRes(badge.id)),
            contentDescription = badge.name,
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
            text = badge.name,
            fontSize = 12.sp,
            color = if (badge.unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = if (badge.unlocked) "已解锁" else "${badge.progressCurrent}/${badge.progressTarget}",
            fontSize = 12.sp,
            color = if (badge.unlocked) LocalRinklColors.current.incomeColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
