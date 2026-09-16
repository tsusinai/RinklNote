package com.example.rinklnote.domain

import com.example.rinklnote.data.db.entity.DailySpendStat
import com.example.rinklnote.util.bookkeepingZone
import java.time.YearMonth

/**
 * 单枚成就的派生状态。徽章 id 与 `resource/challenge/badge-*.svg` 文件名逐字对应（去 badge- 前缀与 .svg 后缀）。
 * 锁定态也给出进度（x/y 展示用）；progressCurrent 封顶到 progressTarget，避免「42/7」这类溢出显示。
 */
data class AchievementState(
    val id: String,
    val unlocked: Boolean,
    val progressCurrent: Long,
    val progressTarget: Long,
)

/**
 * 成就评估输入：全部由确定性数据组装（无持久化解锁标记）。
 * @param recordedDays          全时累计记账天数（COUNT DISTINCT date，deleted=0）
 * @param firstBillDate         首笔账单日期（MIN(date)），null = 从未记过账
 * @param dailyStats            日粒度统计（一天一行，来自 BillDao.observeDailySpendStats）
 * @param budgetOutcomes        逐月预算结算（应与 dailyStats 同源，由 [monthlyBudgetOutcomes] 产出）
 * @param achievedChallengeCount 挑战表 status=ACHIEVED 的总行数（软删 / MISSED 行不计）
 */
data class AchievementInput(
    val recordedDays: Int,
    val firstBillDate: Long?,
    val dailyStats: List<DailySpendStat>,
    val budgetOutcomes: List<MonthBudgetOutcome>,
    val achievedChallengeCount: Int,
)

/**
 * 评估 15 枚成就（返回顺序即成就墙展示顺序）。
 *
 * 防刷口径（全部状态由输入即时推导，删账单 / 删预算 / 挑战状态回退后成就实时回落，无法「先攒后删」保号）：
 * - record-*：累计记账天数与阈值逐一比对；record-first 只看是否存在首笔账；
 * - nospend-month-*：「单月无消费天数」取历史峰值（按业务时区把 dayStart 归桶到自然月）；
 *   无消费日必须当天有记账（billCount>0）且零支出，整天不记账（NO_RECORD）不算——防「不打开 App 刷」；
 * - nospend-total-*：全时累计无消费天数，口径同上；
 * - budget-first / budget-month / budget-3months：只认总额维度预算（[monthlyBudgetOutcomes] 已过滤
 *   分类预算与软删行，同月多行按合计）；「不超支」= budgetMinor != null 且 expenseMinor <= budgetMinor
 *   （恰好相等也算不超支，超一分即超支）；连续 3 月 = 存在连续三个自然月**各自有预算**且都不超支，
 *   中间空档月 / 超支月 / 未设预算月都算断链；
 * - challenge-*：挑战表 ACHIEVED 行数，达标行被删或回退为 MISSED 后成就自动回落。
 *
 * 主题解锁（晨曦 / 薄荷 / 琥珀）不设独立徽章：谓词 = 连续记账 30 天 / 累计无消费 100 天 / 连续 3 月不超支，
 * 由调用方直接用引擎值（currentBookkeepingStreak / 无消费天数 / 本函数 budget-3months 状态）判断。
 * 本函数内所有 epoch → 日期换算固定走业务时区（契约签名不带 zone 参数，输入数据本身也以业务时区产生）。
 */
fun evaluateAchievements(input: AchievementInput): List<AchievementState> {
    val zone = bookkeepingZone()

    // 全时累计无消费天数
    val totalNoSpend = input.dailyStats.count { dayKind(it) == DayKind.NO_SPEND }.toLong()

    // 单月无消费天数峰值：按业务时区把 dayStart 归桶到自然月
    val noSpendByMonth = HashMap<YearMonth, Int>()
    for (stat in input.dailyStats) {
        if (dayKind(stat) != DayKind.NO_SPEND) continue
        val month = YearMonth.from(stat.dayStart.toBookkeepingDate(zone))
        noSpendByMonth[month] = (noSpendByMonth[month] ?: 0) + 1
    }
    val bestMonthNoSpend = (noSpendByMonth.values.maxOrNull() ?: 0).toLong()

    // 有预算且不超支的自然月集合（升序去重）
    val nonOverrunMonths = input.budgetOutcomes
        .filter { it.budgetMinor != null && it.expenseMinor <= it.budgetMinor }
        .map { YearMonth.from(it.monthStart.toBookkeepingDate(zone)) }
        .distinct()
        .sorted()

    // 最长连续不超支月数：按自然月 +1 判连续，中间断一个月（空档 / 超支 / 未设预算）即断
    var bestRun = 0
    var run = 0
    var prev: YearMonth? = null
    for (month in nonOverrunMonths) {
        run = if (prev != null && prev.plusMonths(1) == month) run + 1 else 1
        if (run > bestRun) bestRun = run
        prev = month
    }

    val recorded = input.recordedDays.toLong()
    val challenges = input.achievedChallengeCount.toLong()

    // 解锁判定用原始值；展示进度封顶到 target
    fun state(id: String, rawCurrent: Long, target: Long) = AchievementState(
        id = id,
        unlocked = rawCurrent >= target,
        progressCurrent = rawCurrent.coerceAtMost(target),
        progressTarget = target,
    )

    return listOf(
        state("record-first", if (input.firstBillDate != null) 1L else 0L, 1L),
        state("record-7", recorded, 7L),
        state("record-30", recorded, 30L),
        state("record-100", recorded, 100L),
        state("record-365", recorded, 365L),
        state("nospend-month-3", bestMonthNoSpend, 3L),
        state("nospend-month-8", bestMonthNoSpend, 8L),
        state("nospend-month-15", bestMonthNoSpend, 15L),
        state("nospend-total-30", totalNoSpend, 30L),
        state("nospend-total-100", totalNoSpend, 100L),
        state("budget-first", if (input.budgetOutcomes.any { it.budgetMinor != null }) 1L else 0L, 1L),
        state("budget-month", nonOverrunMonths.size.toLong(), 1L),
        state("budget-3months", bestRun.toLong(), 3L),
        state("challenge-3", challenges, 3L),
        state("challenge-10", challenges, 10L),
    )
}

/** 「我的」卡片徽章展示上限（与服务端 showcase_badges 写入校验一致）。 */
const val MAX_SHOWCASE_BADGES = 3

/**
 * 解析服务端 showcase_badges 逗号分隔串（MeResponse 同形下发）：去空白、去重、封顶 3 枚。
 * null / 空串 → 空列表；永不抛异常（脏数据按空处理）。
 */
fun parseShowcaseBadges(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_SHOWCASE_BADGES)
}

/** 徽章列表 → 逗号分隔串（PUT profile 请求体用；调用方已保证 ≤ 3 枚）。 */
fun joinShowcaseBadges(badges: List<String>): String = badges.joinToString(",")

/** 徽章展示选择：勾选/取消一枚，返回新列表；已达上限再添加时原样返回（UI 层提示）。 */
fun toggleShowcaseBadge(current: List<String>, badgeId: String): List<String> {
    return if (badgeId in current) {
        current - badgeId
    } else {
        if (current.size >= MAX_SHOWCASE_BADGES) current else current + badgeId
    }
}
