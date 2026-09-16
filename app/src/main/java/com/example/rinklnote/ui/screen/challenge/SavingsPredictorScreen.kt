package com.example.rinklnote.ui.screen.challenge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState

/**
 * 结余预测器（路由 `challenge-predictor`，hub「🔮结余预测器」卡进入）：
 * 用本月真实账单聚合画出「月末结余预测」大字报——已花 / 日均 / 预测结余三段大数字，
 * 配「少花一单奶茶钱」的即时情景提示；「少买一点」试算自旧预测卡迁移到本页。
 * 数据全部由 bills 实时派生（ChallengeEngine 口径），本页只做展示翻译与纯 UI 选择。
 */
@Composable
fun SavingsPredictorScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as RinklNoteApp
    val viewModel: ChallengeViewModel = viewModel(
        factory = ChallengeViewModel.Factory(
            app.database.billDao(),
            app.challengeRepository,
            app.budgetRepository
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val predictor = derivePredictor(state)

    val hazeState = remember { HazeState() }
    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (listScrolled) 1f else 0f,
        label = "predictorScrim"
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

                // 预测大字报 hero：薄荷绿（收入/结余语义），预测结余或超支警告做绝对主角。
                item(key = "hero") {
                    PosterCard(
                        pair = pairs.mint,
                        modifier = Modifier.fillMaxWidth().posterBounceEnter(0)
                    ) {
                        Text(
                            text = if (predictor.balanceMinor != null) "月末预测结余" else "月末预测支出",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = pairs.mint.ink.copy(alpha = 0.72f)
                        )
                        HugeNumberText(
                            text = if (predictor.balanceMinor != null) {
                                // 结余带正负号（超支时自然显示 −），统一走 hub 副行的符号口径。
                                heroSurplusText(predictor.balanceMinor ?: 0L)
                            } else {
                                Money.format(predictor.forecastMinor)
                            },
                            ink = pairs.mint.ink,
                            fontSize = 48
                        )
                        Text(
                            text = if (predictor.overBudget) {
                                "按这个花法月底要超支啦，踩一脚刹车！"
                            } else if (predictor.balanceMinor != null) {
                                "已花 ${Money.format(predictor.spentMinor)}，预算 ${Money.format(state.monthBudgetMinor ?: 0L)}"
                            } else {
                                "已花 ${Money.format(predictor.spentMinor)}（设个总额预算可预测结余）"
                            },
                            fontSize = 11.sp,
                            color = pairs.mint.ink.copy(alpha = 0.65f)
                        )
                    }
                }

                // 已花 / 日均 两段大数字（白纸卡并排）。
                item(key = "spent-avg") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberCard(
                            label = "本月已花",
                            value = Money.format(predictor.spentMinor),
                            pair = pairs.paper,
                            order = 1,
                            modifier = Modifier.weight(1f)
                        )
                        NumberCard(
                            label = "日均支出",
                            value = Money.format(predictor.dailyAvgMinor),
                            pair = pairs.paper,
                            order = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 月度时间进度血条 + 奶茶情景提示（电光蓝卡）。
                item(key = "timeline") {
                    PosterCard(
                        pair = pairs.electric,
                        modifier = Modifier.fillMaxWidth().posterBounceEnter(3)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "本月进度",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = pairs.electric.ink,
                                modifier = Modifier.weight(1f)
                            )
                            StickerChip(text = "还剩 ${predictor.daysLeft} 天", rotate = 2f)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        BloodBar(
                            fraction = predictor.monthProgress,
                            fillColor = Color.Black,
                            trackColor = pairs.electric.ink.copy(alpha = 0.18f),
                            segmentCount = 24
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "情景题：剩下的日子每天少喝一杯 ¥${Money.formatPlain(MILK_TEA_PRICE_MINOR)} 奶茶，" +
                                "月底能多留 ${Money.format(predictor.milkTeaSavingMinor)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = pairs.electric.ink.copy(alpha = 0.8f)
                        )
                    }
                }

                // 少买一点：分类 chips + 百分比步进（纯 UI 试算，事件不改库）。
                item(key = "less-buy") {
                    LessBuyCard(predictor = predictor, pairs = pairs, viewModel = viewModel)
                }

                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        PosterTopBar(title = "结余预测器", onBack = onBack, scrimAlpha = topBarScrimAlpha)
    }
}

/** 白纸大数字卡：一段标签 + 一段大数字（预测器的分项数字）。 */
@Composable
private fun NumberCard(
    label: String,
    value: String,
    pair: PosterPair,
    order: Int,
    modifier: Modifier = Modifier,
) {
    PosterCard(pair = pair, modifier = modifier.posterBounceEnter(order)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = pair.ink.copy(alpha = 0.6f)
        )
        HugeNumberText(text = value, ink = pair.ink, fontSize = 26)
    }
}

/** 「少买一点」卡：当月支出 Top 分类 + 百分比步进（10%~100%），实时试算可省金额。 */
@Composable
private fun LessBuyCard(
    predictor: PredictorPosterState,
    pairs: PosterPairs,
    viewModel: ChallengeViewModel,
) {
    PosterCard(pair = pairs.tomato, modifier = Modifier.fillMaxWidth().posterBounceEnter(4)) {
        Text(
            text = "少买一点",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            color = pairs.tomato.ink
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (predictor.categoryTotals.isEmpty()) {
            Text(
                text = "记几笔支出后，可以在这里试算「少买一点」能省多少",
                fontSize = 12.sp,
                color = pairs.tomato.ink.copy(alpha = 0.7f)
            )
        } else {
            // 候选分类：当月支出 Top（横滑一行），点选后实时重算省钱数。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                predictor.categoryTotals.forEach { category ->
                    val selected = category.categoryName == predictor.lessBuyCategory
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color.Black else pairs.tomato.ink.copy(alpha = 0.15f))
                            .clickable {
                                viewModel.onEvent(
                                    ChallengeEvent.SelectLessBuyCategory(
                                        if (selected) null else category.categoryName
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${category.categoryName} ${Money.format(category.totalMinor)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else pairs.tomato.ink
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosterStepButton(symbol = "−", pair = pairs.paper) {
                    viewModel.onEvent(
                        ChallengeEvent.SetLessBuyPercent(predictor.lessBuyPercent - ChallengeViewModel.LESS_BUY_PERCENT_STEP)
                    )
                }
                Text(
                    text = "少买 ${predictor.lessBuyPercent}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = pairs.tomato.ink,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                PosterStepButton(symbol = "+", pair = pairs.paper) {
                    viewModel.onEvent(
                        ChallengeEvent.SetLessBuyPercent(predictor.lessBuyPercent + ChallengeViewModel.LESS_BUY_PERCENT_STEP)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (predictor.lessBuySavingMinor > 0) {
                        "约可省 ${Money.format(predictor.lessBuySavingMinor)}"
                    } else {
                        "选个分类试试"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = pairs.tomato.ink
                )
            }
        }
    }
}
