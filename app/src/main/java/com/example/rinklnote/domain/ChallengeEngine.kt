package com.example.rinklnote.domain

import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.DailySpendStat
import com.example.rinklnote.util.bookkeepingZone
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 省钱挑战 / 成就的派生引擎（纯 JVM：只依赖 java.time + Kotlin 标准库，禁 Android 框架导入）。
 *
 * 统一口径：
 * - 时区：一律锚定业务时区 [bookkeepingZone]（Asia/Shanghai）；涉及日期↔epoch 换算的公开函数
 *   都带 ZoneId 参数且默认业务时区，禁止裸用系统时区；
 * - 日期分组键：「当日 0 点」epoch millis（与 bills.date 的存法一致，23:59 记的账归当天）；
 * - 金额：一律整数分（Long），运算只用整数，禁止引入 Double；
 * - 窗口：[startEpoch, endEpoch) 半开区间（起点含、终点排他）；
 * - 本文件无状态：所有结论由输入即时推导，删账单 / 删预算后结果实时回落（成就防刷的根基）。
 */

/** 某一天的记账形态。NO_SPEND = billCount>0 && expenseMinor==0L（记了账但零支出）。 */
enum class DayKind { NO_RECORD, NO_SPEND, SPEND }

/**
 * 单月预算结算结果（供成就评估与挑战页展示）。
 * @param monthStart   月份键 = 当月 1 日 0 点 epoch millis（业务时区，已归一化）
 * @param budgetMinor  该月总额维度预算（整数分）；null = 该月没有设置总额预算
 * @param expenseMinor 该自然月支出合计（整数分；无账单记 0，受 stats 窗口覆盖限制）
 */
data class MonthBudgetOutcome(
    val monthStart: Long,
    val budgetMinor: Long?,
    val expenseMinor: Long,
)

// ---------- 日期 ↔ epoch 换算（internal 小工具，一律显式带时区，默认业务时区） ----------

/** LocalDate →「当日 0 点」epoch millis（与 bills.date 分组键同口径）。 */
internal fun LocalDate.toDayStartEpoch(zone: ZoneId = bookkeepingZone()): Long =
    atStartOfDay(zone).toInstant().toEpochMilli()

/** epoch millis → 业务时区下的 LocalDate。 */
internal fun Long.toBookkeepingDate(zone: ZoneId = bookkeepingZone()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

// ---------- 单日形态与天数统计 ----------

/**
 * 单日形态判定：
 * - stat 为 null 或 billCount == 0 → NO_RECORD（整天没有记账动作）；
 * - billCount > 0 且 expenseMinor == 0 → NO_SPEND（记了账但一分钱没花，含只记收入的日子）；
 * - 其余 → SPEND。
 * 防刷关键：无消费日必须「有记账动作」，整天不打开账本不算无消费日。
 */
fun dayKind(stat: DailySpendStat?): DayKind = when {
    stat == null || stat.billCount <= 0 -> DayKind.NO_RECORD
    stat.expenseMinor == 0L -> DayKind.NO_SPEND
    else -> DayKind.SPEND
}

/**
 * 统计半开区间 [startEpoch, endEpoch) 内的「无消费日」天数。
 * stats 本身一天一行（dayStart = 当日 0 点），直接按行窗口过滤即可，无需再做时区换算。
 */
fun noSpendDaysBetween(stats: List<DailySpendStat>, startEpoch: Long, endEpoch: Long): Int =
    stats.count { it.dayStart >= startEpoch && it.dayStart < endEpoch && dayKind(it) == DayKind.NO_SPEND }

/**
 * 当前连续记账天数（含今天；今天没记按昨天活着算——给「今天还没来得及记」留缓冲，避免白天看页面断签）。
 * NO_SPEND（只记收入）也算记账，只有 NO_RECORD 断签；从今天往回数到第一个没记账的日子为止。
 */
fun currentBookkeepingStreak(
    stats: List<DailySpendStat>,
    today: LocalDate,
    zone: ZoneId = bookkeepingZone(),
): Int {
    val kindByDay = HashMap<Long, DayKind>(stats.size)
    for (stat in stats) kindByDay[stat.dayStart] = dayKind(stat)
    fun kindAt(date: LocalDate): DayKind = kindByDay[date.toDayStartEpoch(zone)] ?: DayKind.NO_RECORD
    // 今天没记 → 从昨天开始数；今天记了（含只记收入）→ 从今天开始数
    var cursor = if (kindAt(today) == DayKind.NO_RECORD) today.minusDays(1) else today
    var streak = 0
    while (kindAt(cursor) != DayKind.NO_RECORD) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** 历史最长连续记账天数（NO_SPEND / SPEND 都算活着；按自然日 +1 判连续，隔一天即断）。 */
fun longestBookkeepingStreak(stats: List<DailySpendStat>, zone: ZoneId = bookkeepingZone()): Int {
    val days = stats.filter { dayKind(it) != DayKind.NO_RECORD }
        .map { it.dayStart.toBookkeepingDate(zone) }
        .distinct()
        .sorted()
    var best = 0
    var run = 0
    var prev: LocalDate? = null
    for (day in days) {
        run = if (prev != null && prev.plusDays(1) == day) run + 1 else 1
        if (run > best) best = run
        prev = day
    }
    return best
}

// ---------- 区间支出 ----------

/** 半开区间 [startEpoch, endEpoch) 内的支出合计（整数分；stats.expenseMinor 已只含支出、不含收入）。 */
fun expenseBetween(stats: List<DailySpendStat>, startEpoch: Long, endEpoch: Long): Long =
    stats.filter { it.dayStart >= startEpoch && it.dayStart < endEpoch }.sumOf { it.expenseMinor }

// ---------- 周期口径 ----------

/** 周起点 = 本周周一（previousOrSame：周日会回退到本周一，周一返回自身）。 */
fun weekStartOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** 月挑战 periodStart 口径：当月 1 日。 */
fun monthStartOf(date: LocalDate): LocalDate = date.withDayOfMonth(1)

/** 月挑战周期排他终点：次月 1 日（大小月 / 闰年由 java.time 处理，2 月 → 3 月 1 日）。 */
fun monthEndExclusive(date: LocalDate): LocalDate = date.withDayOfMonth(1).plusMonths(1)

/**
 * 周期还剩多少天：含今天、不含 periodEndExclusive 当天。
 * periodEndExclusive 为「排他终点」epoch（如次日 0 点 = 周挑战的下周一 0 点），
 * 按业务时区转 LocalDate 后做日差；已过期（终点日 ≤ 今天）返回 0，不返回负数。
 */
fun remainingDays(periodEndExclusive: Long, today: LocalDate, zone: ZoneId = bookkeepingZone()): Int {
    val end = periodEndExclusive.toBookkeepingDate(zone)
    return ChronoUnit.DAYS.between(today, end).coerceAtLeast(0).toInt()
}

// ---------- 预测与「少买一点」 ----------

/**
 * 按日均外推整月支出：totalSpentMinor ÷ dayOfMonth × daysInMonth。
 * 取整策略：四舍五入（HALF_UP）到整数分，纯整数运算 (total × days + day ÷ 2) ÷ day，无浮点漂移；
 * dayOfMonth ≤ 0 或 daysInMonth ≤ 0 时无法外推，原样返回 totalSpentMinor（调用方正常传 1..31，此为兜底）。
 */
fun forecastMonth(totalSpentMinor: Long, dayOfMonth: Int, daysInMonth: Int): Long {
    if (dayOfMonth <= 0 || daysInMonth <= 0) return totalSpentMinor
    val scaled = totalSpentMinor * daysInMonth
    return (scaled + dayOfMonth / 2) / dayOfMonth
}

/**
 * 「少买一点」节省额：categoryTotalMinor × percent ÷ 100。
 * 取整：HALF_UP 到分（整数运算 (值 × percent + 50) ÷ 100）；
 * percent 越界时夹取到 [10, 100]——滑杆范围兜底，防止异常入参算出超过品类总额的「节省额」。
 */
fun lessBuySaving(categoryTotalMinor: Long, percent: Int): Long {
    val p = percent.coerceIn(10, 100)
    return (categoryTotalMinor * p + 50) / 100
}

// ---------- 预算燃烧风险（Task 4.1 预算-挑战联动，App/Web 口径钉死） ----------

/** 风险档位：<85 低、85~100 中、>100 高（边界值 85/100 归中风险，见 [budgetRiskLevel]）。 */
enum class BudgetRiskLevel { LOW, MEDIUM, HIGH }

/** 档位阈值（百分比）：与 Web 端图表页预警标记同源常量，改动须三端同步。 */
const val BUDGET_RISK_MEDIUM_PCT = 85.0
const val BUDGET_RISK_HIGH_PCT = 100.0

/**
 * 预算燃烧风险派生结果（全部实时推导，零存储）。
 * @param pct 预测到周期末的累计消耗 ÷ 周期预算 × 100（预测值按燃烧速度外推，HALF_UP 到分）
 * @param level 档位（<85 低 / 85~100 中 / >100 高）
 * @param forecastMinor 预测到周期末的累计消耗（整数分）
 */
data class BudgetBurnRisk(
    val pct: Double,
    val level: BudgetRiskLevel,
    val forecastMinor: Long
)

/**
 * 档位判定（钉死口径）：pct < 85 → LOW；85 ≤ pct ≤ 100 → MEDIUM；pct > 100 → HIGH。
 * 边界值 85 与 100 都归**中风险**（预警提前、达成缓冲的最后一线）。
 */
fun budgetRiskLevel(pct: Double): BudgetRiskLevel = when {
    pct < BUDGET_RISK_MEDIUM_PCT -> BudgetRiskLevel.LOW
    pct <= BUDGET_RISK_HIGH_PCT -> BudgetRiskLevel.MEDIUM
    else -> BudgetRiskLevel.HIGH
}

/**
 * 预算燃烧风险：按当前燃烧速度（spent ÷ elapsedDays）外推到周期末的累计消耗，
 * 与周期预算的比值定档。口径与 [forecastMonth] 一致（HALF_UP 整数运算），纯整数求预测、
 * 仅百分比一步转 Double（与预算页 progress 同哲学）。
 *
 * @param spentMinor 周期已消耗（整数分）
 * @param budgetMinor 周期预算（整数分；≤0 = 未设预算，返回 null 不预警）
 * @param elapsedDays 已过天数（含今天；≤0 无法外推 → null）
 * @param totalDays 周期总天数（≤0 无法外推 → null）
 */
fun budgetBurnRisk(
    spentMinor: Long,
    budgetMinor: Long,
    elapsedDays: Int,
    totalDays: Int,
): BudgetBurnRisk? {
    if (budgetMinor <= 0 || elapsedDays <= 0 || totalDays <= 0) return null
    // 预测累计 = spent × total ÷ elapsed（HALF_UP，与 forecastMonth 同式）
    val forecast = (spentMinor * totalDays + elapsedDays / 2) / elapsedDays
    val pct = forecast * 100.0 / budgetMinor
    return BudgetBurnRisk(pct = pct, level = budgetRiskLevel(pct), forecastMinor = forecast)
}

// ---------- 逐月预算结算（成就评估输入） ----------

/**
 * 逐月结算「总额维度预算」，产出 [MonthBudgetOutcome] 列表（按 monthStart 升序）。防刷与归一口径：
 * - 只取 categoryId 与 subCategoryId 均为空的预算行（分类 / 子分类预算不参与月结算与成就，
 *   防止用单分类小预算刷「不超支」）；
 * - 软删除（deleted = true）的行剔除；
 * - 同一自然月出现多行总额预算（数据异常）按合计计入，防止拆分小额预算刷成就；
 * - 月份键统一归一化为「当月 1 日 0 点」（业务时区），与 stats 的日分组键同一时区口径；
 * - 除预算月外，把「有账单但没预算」的自然月也补进结果（budgetMinor = null），
 *   供连续性判定与「本月未设预算」类展示；
 * - 支出按 stats 逐行归桶到自然月求和，受 stats 窗口覆盖限制（挑战页传 今天-400 天 ~ 明天），
 *   调用方需保证窗口覆盖所需月份。
 */
fun monthlyBudgetOutcomes(
    stats: List<DailySpendStat>,
    budgets: List<Budget>,
    zone: ZoneId = bookkeepingZone(),
): List<MonthBudgetOutcome> {
    // 月份键（当月 1 日 0 点）→ 支出合计
    val expenseByMonth = HashMap<Long, Long>()
    for (stat in stats) {
        val month = monthStartOf(stat.dayStart.toBookkeepingDate(zone)).toDayStartEpoch(zone)
        expenseByMonth[month] = (expenseByMonth[month] ?: 0L) + stat.expenseMinor
    }
    // 月份键 → 总额维度预算合计（分类预算 / 软删行剔除，同月多行合计）
    val budgetByMonth = HashMap<Long, Long>()
    for (budget in budgets) {
        if (budget.deleted) continue
        if (budget.categoryId != null || budget.subCategoryId != null) continue
        val month = monthStartOf(budget.monthStart.toBookkeepingDate(zone)).toDayStartEpoch(zone)
        budgetByMonth[month] = (budgetByMonth[month] ?: 0L) + budget.amountMinor
    }
    return (expenseByMonth.keys + budgetByMonth.keys)
        .toSortedSet()
        .map { month ->
            MonthBudgetOutcome(
                monthStart = month,
                budgetMinor = budgetByMonth[month],
                expenseMinor = expenseByMonth[month] ?: 0L,
            )
        }
}
