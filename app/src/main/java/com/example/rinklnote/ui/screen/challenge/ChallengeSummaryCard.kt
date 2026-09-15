package com.example.rinklnote.ui.screen.challenge

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Challenge
import com.example.rinklnote.data.db.entity.ChallengeType
import com.example.rinklnote.data.db.entity.DailySpendStat
import com.example.rinklnote.domain.currentBookkeepingStreak
import com.example.rinklnote.domain.expenseBetween
import com.example.rinklnote.domain.forecastMonth
import com.example.rinklnote.domain.monthEndExclusive
import com.example.rinklnote.domain.monthStartOf
import com.example.rinklnote.domain.noSpendDaysBetween
import com.example.rinklnote.domain.toDayStartEpoch
import com.example.rinklnote.domain.weekStartOf
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.Money
import java.time.LocalDate
import kotlinx.coroutines.flow.combine

/**
 * 「省钱挑战」摘要卡：计划页 LazyColumn 首位的入口卡（整卡可点 → 挑战页）。
 *
 * 与挑战页共用一套口径（镜像 [ChallengeViewModel] 的取数与派生方式）：
 * - 取数：RinklNoteApp 定位器自取「日统计 Flow + 挑战表 Flow + 预算表 Flow」，窗口 = 今天 −400 天 ~ 明天；
 * - 派生：[ChallengeEngine]（domain）现算，纯整数分，删账单/删预算后实时回落；
 * - 只读展示，不在本卡补挑战行（幂等补行是挑战页 ViewModel 的职责，避免入口卡产生写副作用）。
 *
 * @param onOpenChallenges 整卡点击回调（导航层注入，跳 `challenges` 路由）
 */
@Composable
fun ChallengeSummaryCard(onOpenChallenges: () -> Unit) {
    val app = LocalContext.current.applicationContext as RinklNoteApp
    var signals by remember { mutableStateOf<ChallengeSummarySignals?>(null) }

    // 自取数：三流合流现算（与挑战页同一窗口与派生口径，保证两处数字永远一致）。
    LaunchedEffect(Unit) {
        combine(
            app.database.billDao().observeDailySpendStats(observationWindowStart(), observationWindowEnd()),
            app.challengeRepository.observeAll(),
            app.budgetRepository.observeBudgets(),
        ) { stats, challenges, budgets ->
            deriveChallengeSummary(
                today = LocalDate.now(bookkeepingZone()),
                stats = stats,
                challenges = challenges,
                budgets = budgets,
            )
        }.collect { derived -> signals = derived }
    }

    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .clickable(onClick = onOpenChallenges)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 头部：存钱罐图标 + 标题 + 右箭头（对齐「分类预算设置」入口行的观感）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.icon_challenge_piggy),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "省钱挑战",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = LocalRinklColors.current.iconButtonColor
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        val current = signals
        if (current == null) {
            // 首帧 Flow 尚未发射：垫住卡片高度，避免布局跳动。
            Spacer(modifier = Modifier.defaultMinSize(minHeight = 48.dp))
        } else {
            SummaryProgressLine(
                label = "本月无消费",
                value = "${current.monthNoSpendDays}/${current.noSpendGoal} 天",
                progress = if (current.noSpendGoal > 0) {
                    (current.monthNoSpendDays.toDouble() / current.noSpendGoal).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                }
            )
            SummaryProgressLine(
                label = "连续记账",
                value = "${current.streakDays} 天",
                // 进度条按 30 天刻度（晨曦主题解锁阈值），满格即达成里程碑。
                progress = (current.streakDays.toDouble() / 30.0).toFloat().coerceIn(0f, 1f)
            )
            if (current.weekGoalMinor > 0) {
                SummaryProgressLine(
                    label = "本周支出",
                    value = "${Money.format(current.weekExpenseMinor)} / 上限 ${Money.format(current.weekGoalMinor)}",
                    progress = (current.weekExpenseMinor.toDouble() / current.weekGoalMinor)
                        .toFloat()
                        .coerceIn(0f, 1f),
                    over = current.weekExpenseMinor > current.weekGoalMinor
                )
            } else {
                SummaryProgressLine(
                    label = "本周支出",
                    value = "${Money.format(current.weekExpenseMinor)} · 未设上限",
                    progress = null
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            SummaryForecastLine(current)
        }
    }
}

/** 单行迷你进度：左标签 + 右数值 + 底部细进度条（progress = null 时不画条）。 */
@Composable
private fun SummaryProgressLine(
    label: String,
    value: String,
    progress: Float?,
    over: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

/** 结余预测一句：设了总额预算给「预计结余 / 预计超支」，未设给月底支出预测；一笔账没有给引导。 */
@Composable
private fun SummaryForecastLine(signals: ChallengeSummarySignals) {
    when {
        !signals.hasAnyBill -> Text(
            text = "记下第一笔账，省钱挑战与成就就开始",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        signals.forecastBalanceMinor == null -> Text(
            text = "预计月底支出 ${Money.format(signals.forecastMinor)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        signals.forecastBalanceMinor >= 0 -> Text(
            text = "按这个花法预计结余 ${Money.format(signals.forecastBalanceMinor)}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = LocalRinklColors.current.incomeColor
        )
        else -> Text(
            text = "按这个花法预计超支 ${Money.format(-signals.forecastBalanceMinor)}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** 摘要卡三行进度 + 预测一句的现算结果（全部整数分，口径与挑战页 ChallengeState 对应字段一致）。 */
internal data class ChallengeSummarySignals(
    val hasAnyBill: Boolean,
    val monthNoSpendDays: Int,
    val noSpendGoal: Long,
    val streakDays: Int,
    val weekExpenseMinor: Long,
    val weekGoalMinor: Long,
    val forecastMinor: Long,
    /** 预计结余 = 当月总额预算 − 月底支出预测；null = 本月未设总额预算。 */
    val forecastBalanceMinor: Long?,
)

/** 纯函数：摘要卡状态派生（镜像 deriveChallengeState 的对应子集，独立可测）。 */
internal fun deriveChallengeSummary(
    today: LocalDate,
    stats: List<DailySpendStat>,
    challenges: List<Challenge>,
    budgets: List<Budget>,
): ChallengeSummarySignals {
    val zone = bookkeepingZone()
    val monthStart = monthStartOf(today).toDayStartEpoch(zone)
    val monthEnd = monthEndExclusive(today).toDayStartEpoch(zone)
    val weekStartDate = weekStartOf(today)
    val weekStart = weekStartDate.toDayStartEpoch(zone)
    val weekEnd = weekStartDate.plusWeeks(1).toDayStartEpoch(zone)

    // 当期挑战行目标：无行时无消费日给默认 8 天（与挑战页展示口径一致）；周上限无行按未设处理。
    val noSpendGoal = challenges
        .firstOrNull {
            it.type == ChallengeType.NO_SPEND_DAY && it.periodStart == monthStart && !it.deleted
        }?.goal ?: ChallengeViewModel.NO_SPEND_GOAL_DEFAULT
    val weekGoal = challenges
        .firstOrNull {
            it.type == ChallengeType.WEEKLY_BUDGET && it.periodStart == weekStart && !it.deleted
        }?.goal ?: 0L

    val monthExpense = expenseBetween(stats, monthStart, monthEnd)
    val forecast = forecastMonth(monthExpense, today.dayOfMonth, today.lengthOfMonth())
    // 当月总额维度预算合计：分类/子分类预算与软删行剔除（口径与挑战页 forecastBalance 一致）。
    val monthBudget = budgets
        .filter {
            !it.deleted && it.categoryId == null && it.subCategoryId == null && it.monthStart == monthStart
        }
        .sumOf { it.amountMinor }
        .takeIf { it > 0 }

    return ChallengeSummarySignals(
        hasAnyBill = stats.isNotEmpty(),
        monthNoSpendDays = noSpendDaysBetween(stats, monthStart, monthEnd),
        noSpendGoal = noSpendGoal,
        streakDays = currentBookkeepingStreak(stats, today),
        weekExpenseMinor = expenseBetween(stats, weekStart, weekEnd),
        weekGoalMinor = weekGoal,
        forecastMinor = forecast,
        forecastBalanceMinor = monthBudget?.let { it - forecast },
    )
}

/** 日统计观察窗口：今天 −400 天 ~ 明天（与 ChallengeViewModel 同一契约口径）。 */
private fun observationWindowStart(): Long =
    LocalDate.now(bookkeepingZone()).minusDays(400).toDayStartEpoch()

private fun observationWindowEnd(): Long =
    LocalDate.now(bookkeepingZone()).plusDays(1).toDayStartEpoch()
