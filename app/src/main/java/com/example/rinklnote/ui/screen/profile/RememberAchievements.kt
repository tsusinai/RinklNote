package com.example.rinklnote.ui.screen.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.domain.AchievementInput
import com.example.rinklnote.domain.AchievementState
import com.example.rinklnote.domain.evaluateAchievements
import com.example.rinklnote.domain.monthlyBudgetOutcomes
import com.example.rinklnote.domain.toDayStartEpoch
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.util.bookkeepingZone
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/**
 * 订阅「日统计 + 预算 + 挑战」三流实时派生 15 枚成就（ChallengeViewModel 同一口径）：
 * 窗口 = 今天 −400 天 ~ 明天（业务时区）；删账单 / 预算回退后自动回落，无需手动刷新。
 *
 * 「我的」卡片徽章行与个人资料页徽章展示管理共用；默认返回空列表（数据未就绪时徽章行不显示）。
 */
@Composable
internal fun rememberAchievementStates(): State<List<AchievementState>> {
    val app = LocalContext.current.applicationContext as RinklNoteApp
    return produceState(initialValue = emptyList(), app) {
        val zone = bookkeepingZone()
        val now = LocalDate.now(zone)
        combine(
            app.database.billDao().observeDailySpendStats(
                now.minusDays(400).toDayStartEpoch(zone),
                now.plusDays(1).toDayStartEpoch(zone)
            ),
            app.budgetRepository.observeBudgets(),
            app.challengeRepository.observeAll()
        ) { stats, budgets, challenges ->
            evaluateAchievements(
                AchievementInput(
                    recordedDays = app.database.billDao().countRecordedDays(),
                    firstBillDate = app.database.billDao().getFirstBillDate(),
                    dailyStats = stats,
                    budgetOutcomes = monthlyBudgetOutcomes(stats, budgets),
                    achievedChallengeCount = challenges.count {
                        it.status == ChallengeStatus.ACHIEVED && !it.deleted
                    }
                )
            )
        }.collect { value = it }
    }
}
