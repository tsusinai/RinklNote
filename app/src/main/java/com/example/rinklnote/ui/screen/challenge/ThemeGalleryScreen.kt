package com.example.rinklnote.ui.screen.challenge

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.screen.profile.UnlockableThemePreset
import com.example.rinklnote.ui.screen.profile.UnlockableThemePresets
import com.example.rinklnote.ui.theme.RinklThemeSlot
import com.example.rinklnote.ui.theme.colorToHex
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * 主题换装间（路由 `challenge-themes`，hub「🎨主题换装间」卡进入）：
 * 已解锁主题（晨曦 / 薄荷 / 琥珀）陈列与预览切换——解锁谓词由引擎值实时派生（零存储照旧），
 * 应用动作复用「自定义主题」页同一套 setCustomThemeColor 全槽写入，未解锁显示条件文案。
 * 撞色预览随当前明暗态取对应套色值；当前使用中的主题卡高亮「使用中」贴纸。
 */
@Composable
fun ThemeGalleryScreen(
    onBack: () -> Unit,
    onOpenCustomTheme: () -> Unit = {},
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

    // 当前自定义主题（换装间「使用中」判定与预览基准）；明暗态镜像 MainActivity 的解析。
    val custom by app.settingsManager.customThemeColors
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val themeMode by app.settingsManager.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val currentPrimaryHex = custom[RinklThemeSlot.PRIMARY]?.let { colorToHex(it) }
    val cards = deriveThemeCards(state, currentPrimaryHex)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (listScrolled) 1f else 0f,
        label = "themeGalleryScrim"
    )
    val pairs = rememberPosterPairs()

    Box(modifier = Modifier.fillMaxSize()) {
        DefaultHazeBackground(hazeState = hazeState)

        if (!state.isLoading && state.hasAnyBill) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

                // 换装间 hero：白纸大字报说明玩法。
                item(key = "hero") {
                    PosterCard(
                        pair = pairs.paper,
                        modifier = Modifier.fillMaxWidth().posterBounceEnter(0)
                    ) {
                        Text(
                            text = "挑战成就解锁限定皮肤",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            color = pairs.paper.ink
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "达成挑战条件即解锁，点「换上」整套应用（主题色 / 边框 / 图标 / 字体 / 顶栏五槽）",
                            fontSize = 11.sp,
                            color = pairs.paper.ink.copy(alpha = 0.6f)
                        )
                    }
                }

                // 三套解锁主题卡：解锁=可换装；未解锁=锁 + 条件；使用中=金色高亮贴纸。
                items(cards.size) { index ->
                    val card = cards[index]
                    val preset = UnlockableThemePresets.firstOrNull { it.name == card.name }
                    if (preset != null) {
                        ThemeCard(
                            card = card,
                            preset = preset,
                            isDark = isDark,
                            pairs = pairs,
                            order = index + 1,
                            onApply = {
                                if (card.unlocked) {
                                    val colors = if (isDark) preset.dark else preset.light
                                    scope.launch {
                                        // 与「自定义主题」页完全同一套写入：五槽整套应用，即时全局生效。
                                        app.settingsManager.setCustomThemeColor(RinklThemeSlot.PRIMARY, colors.themeColor)
                                        app.settingsManager.setCustomThemeColor(RinklThemeSlot.BORDER, colors.borderColor)
                                        app.settingsManager.setCustomThemeColor(RinklThemeSlot.ICON, colors.iconColor)
                                        app.settingsManager.setCustomThemeColor(RinklThemeSlot.FONT, colors.fontColor)
                                        app.settingsManager.setCustomThemeColor(RinklThemeSlot.TOP_BAR, colors.topBarColor)
                                    }
                                    Toast.makeText(context, "已换上「${preset.name}」", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "完成「${preset.requirement}」后解锁", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                // 微调入口：去自定义主题页（全局既有路由）。
                item(key = "fine-tune") {
                    PosterCard(
                        pair = pairs.electric,
                        onClick = onOpenCustomTheme,
                        modifier = Modifier.fillMaxWidth().posterBounceEnter(4)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🎨 想自己调色？",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = pairs.electric.ink
                                )
                                Text(
                                    text = "十个颜色槽随心改，去自定义主题页",
                                    fontSize = 11.sp,
                                    color = pairs.electric.ink.copy(alpha = 0.75f)
                                )
                            }
                            StickerChip(text = "去微调 →", rotate = 2f)
                        }
                    }
                }

                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        PosterTopBar(title = "主题换装间", onBack = onBack, scrimAlpha = topBarScrimAlpha)
    }
}

/** 换装间单张主题卡：撞色预览（按明暗取套）+ 色点行 + 状态贴纸 + 换装按钮。 */
@Composable
private fun ThemeCard(
    card: ThemeCardState,
    preset: UnlockableThemePreset,
    isDark: Boolean,
    pairs: PosterPairs,
    order: Int,
    onApply: () -> Unit,
) {
    val colors = if (isDark) preset.dark else preset.light
    PosterCard(
        pair = PosterPair(
            background = if (card.unlocked) {
                posterizeVivid(colors.themeColor, isDark)
            } else {
                Color(0xFFE0E0E0) // 未解锁：灰底贴纸，解锁后换真彩
            },
            ink = if (card.unlocked) Color.Black else Color(0xFF616161)
        ),
        onClick = onApply,
        modifier = Modifier.fillMaxWidth().posterBounceEnter(order)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = if (card.unlocked) Color.Black else Color(0xFF616161)
                )
                Text(
                    text = card.condition,
                    fontSize = 11.sp,
                    color = if (card.unlocked) Color.Black.copy(alpha = 0.6f) else Color(0xFF9E9E9E)
                )
            }
            if (card.unlocked) {
                StickerButton(
                    text = if (card.inUse) "使用中" else "换上",
                    pair = if (card.inUse) pairs.paper else PosterPair(Color.Black, Color.White),
                    onClick = onApply
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "未解锁",
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (card.unlocked) {
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                listOfNotNull(colors.themeColor, colors.iconColor, colors.borderColor).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(c)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                StickerChip(text = "${preset.requirement} ✓", rotate = -2f)
            }
        }
    }
}
