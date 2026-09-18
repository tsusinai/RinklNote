package com.example.rinklnote.server.services.coach

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.TimeUtil
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 账单教练（2026-09-18 Task 1.4）：周聚合 → 结构化可执行建议（类型 / 文案 / 关联 challenge 或 budget id）。
 *
 * **隐私红线**：输入只有「分类 + 金额 + 日期」的聚合结果（窗口支出合计、Top 分类），
 * 绝不读账单备注 / 明细行；文案只引用聚合数字。
 *
 * 派生口径与 App `domain/ChallengeEngine.kt` 一致：
 * - 时区 Asia/Shanghai（[TimeUtil.BOOKKEEPING_ZONE]）；日期分组键 = 当日 0 点 epoch 毫秒；
 * - 周起点 = 本周周一（previousOrSame）；窗口一律 [start, end) 半开区间；
 * - 金额整数分，线性外推 / 基准全部纯整数运算 + HALF_UP（见 [linearBaselineMinor] /
 *   [forecastPeriodMinor]，与 App `forecastMonth` 同一个 `(值×倍数 + 除数/2) ÷ 除数` 公式）；
 * - 纯函数零状态：删账单 / 删预算后结论实时回落。
 */
class CoachService(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val challengeService: ChallengeService,
    private val zone: ZoneId = TimeUtil.BOOKKEEPING_ZONE
) {

    /** 周消耗速度档位。 */
    enum class BurnLevel { ON_TRACK, FAST, OVER }

    /** 结构化建议（直接可序列化为推送/接口输出）。 */
    @Serializable
    data class CoachAdvice(
        // WEEKLY_BURN_OVER / WEEKLY_BURN_FAST / WEEKLY_ON_TRACK / MONTH_FORECAST_OVER
        val type: String,
        val text: String,
        val level: String, // WARN / INFO
        val budgetId: Long? = null,
        val challengeId: Long? = null
    )

    // ── 纯函数（与 App ChallengeEngine 同口径，测试向量写死在测试里）──

    companion object {
        /**
         * 线性基准：预算 × 已过天数 ÷ 周期天数，整数 HALF_UP。
         * 与 App `forecastMonth` 的 `(值 × 倍数 + 除数/2) ÷ 除数` 同公式。
         */
        fun linearBaselineMinor(budgetMinor: Long, daysElapsed: Int, periodDays: Int): Long =
            if (daysElapsed <= 0 || periodDays <= 0) budgetMinor
            else (budgetMinor * daysElapsed + periodDays / 2) / periodDays

        /**
         * 周期期末外推：已花 × 周期天数 ÷ 已过天数，整数 HALF_UP（App forecastMonth 同口径）。
         */
        fun forecastPeriodMinor(spentMinor: Long, daysElapsed: Int, periodDays: Int): Long =
            if (daysElapsed <= 0 || periodDays <= 0) spentMinor
            else (spentMinor * periodDays + daysElapsed / 2) / daysElapsed

        /**
         * 周烧穿档位（按期末外推 vs 周预算分两档）：
         *  - OVER：外推超预算 10% 以上（预测 ×10 > 预算 ×11），紧急刹车；
         *  - FAST：外推会烧穿但幅度 ≤10%（把大头收一收还来得及）；
         *  - ON_TRACK：外推 ≤ 预算。
         * 说明：`已花 > 线性基准` 与 `外推 > 预算` 在整数分下几乎等价（差 < 1 分的舍入带），
         * 故 FAST 用「外推小幅超预算」承载「比线性节奏快」的语义，避免出现永不命中的空档。
         */
        fun burnLevel(spentMinor: Long, baselineMinor: Long, forecastMinor: Long, budgetMinor: Long): BurnLevel = when {
            forecastMinor * 10 > budgetMinor * 11 -> BurnLevel.OVER
            forecastMinor > budgetMinor -> BurnLevel.FAST
            else -> BurnLevel.ON_TRACK
        }

        /** 周起点 = 本周周一（与 App `weekStartOf` 同口径）。 */
        fun weekStartOf(date: LocalDate): LocalDate =
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    // ── 聚合与建议生成 ──

    /**
     * 本周（+本月）结构化建议。无预算且无周挑战 → 空列表（不烦人）。
     * 只消费聚合（窗口支出合计 + Top 分类名），无明细无备注。
     */
    fun weeklyAdvice(userId: Long, today: LocalDate = TimeUtil.today()): List<CoachAdvice> {
        val advices = mutableListOf<CoachAdvice>()
        val weekStart = weekStartOf(today)
        val weekStartEpoch = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextWeekEpoch = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val daysElapsed = ChronoUnit.DAYS.between(weekStart, today).toInt() + 1 // 含今天

        val weekStats = billService.monthlyStats(userId, weekStartEpoch, nextWeekEpoch)
        val weekSpent = weekStats.totalExpenseMinor
        val topCategory = weekStats.topExpenseCategories.firstOrNull()?.first
        val topTip = topCategory?.let { "，大头是$it" } ?: ""

        // 1) 周预算挑战（WEEKLY_BUDGET，periodStart = 本周一，ACTIVE；goal = 整数分）
        val weeklyChallenge = challengeService.list(userId).firstOrNull {
            it.type == "WEEKLY_BUDGET" && it.periodStart == weekStartEpoch && !it.deleted && it.status == "ACTIVE"
        }
        if (weeklyChallenge != null && weeklyChallenge.goal > 0) {
            val baseline = linearBaselineMinor(weeklyChallenge.goal, daysElapsed, 7)
            val forecast = forecastPeriodMinor(weekSpent, daysElapsed, 7)
            when (burnLevel(weekSpent, baseline, forecast, weeklyChallenge.goal)) {
                BurnLevel.OVER -> advices += CoachAdvice(
                    type = "WEEKLY_BURN_OVER", level = "WARN", challengeId = weeklyChallenge.id,
                    text = "本周已花 ¥${Money.format(weekSpent)}$topTip，照这个速度周末预计到 " +
                        "¥${Money.format(forecast)}，远超周预算 ¥${Money.format(weeklyChallenge.goal)}，" +
                        "接下来几天先管住${topCategory ?: "大头"}吧"
                )
                BurnLevel.FAST -> advices += CoachAdvice(
                    type = "WEEKLY_BURN_FAST", level = "WARN", challengeId = weeklyChallenge.id,
                    text = "本周过了 $daysElapsed/7 天（线性节奏 ¥${Money.format(baseline)}），照此速度周末预计 " +
                        "¥${Money.format(forecast)}，会小幅超出周预算 ¥${Money.format(weeklyChallenge.goal)}，" +
                        "把大头收一收还来得及～"
                )
                BurnLevel.ON_TRACK -> advices += CoachAdvice(
                    type = "WEEKLY_ON_TRACK", level = "INFO", challengeId = weeklyChallenge.id,
                    text = "本周已花 ¥${Money.format(weekSpent)}，节奏稳稳在线性基准（¥${Money.format(baseline)}）以内，保持～"
                )
            }
        }

        // 2) 月总额预算期末外推预警（只取无分类预算行，与 App monthlyBudgetOutcomes 同口径）
        val monthStart = today.withDayOfMonth(1)
        val monthStartEpoch = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthEpoch = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthlyBudget = budgetService.list(userId).firstOrNull {
            !it.deleted && it.categoryId == null && it.subCategoryId == null && it.monthStart == monthStartEpoch
        }
        if (monthlyBudget != null && monthlyBudget.amountMinor > 0 && today.dayOfMonth < today.lengthOfMonth()) {
            val monthSpent = billService.monthlyStats(userId, monthStartEpoch, nextMonthEpoch).totalExpenseMinor
            val forecast = forecastPeriodMinor(monthSpent, today.dayOfMonth, today.lengthOfMonth())
            if (forecast > monthlyBudget.amountMinor) {
                advices += CoachAdvice(
                    type = "MONTH_FORECAST_OVER", level = "WARN", budgetId = monthlyBudget.id,
                    text = "按目前节奏月底预计花 ¥${Money.format(forecast)}，会超出月预算 " +
                        "¥${Money.format(monthlyBudget.amountMinor)}，本周能省一点是一点～"
                )
            }
        }
        return advices
    }

    /**
     * 周报推送文案（PushScheduler WEEKLY_REPORT 周一挂接）：无建议返回 null（不推）。
     * 文案仅含聚合数字与分类名。
     */
    fun weeklyPushCopy(userId: Long, today: LocalDate = TimeUtil.today()): String? {
        val advices = weeklyAdvice(userId, today)
        if (advices.isEmpty()) return null
        return buildString {
            appendLine("🧾 账单教练·本周提醒")
            advices.forEach { appendLine("· ${it.text}") }
        }.trimEnd()
    }
}
