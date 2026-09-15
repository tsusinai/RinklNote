package com.example.rinklnote.domain

import com.example.rinklnote.data.db.entity.DailySpendStat
import com.example.rinklnote.util.bookkeepingZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 成就评估单测：15 枚徽章 id 与 `resource/challenge/badge-*.svg` 文件名逐字一致。
 * 全部用固定 LocalDate + 业务时区构造 epoch，不依赖系统时钟，离线可跑。
 */
class AchievementsTest {

    private val zone: ZoneId = bookkeepingZone()

    private fun epoch(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun day(date: LocalDate, billCount: Int = 1, expenseMinor: Long = 0L) =
        DailySpendStat(dayStart = epoch(date), billCount = billCount, expenseMinor = expenseMinor)

    private fun input(
        recordedDays: Int = 0,
        firstBillDate: Long? = null,
        dailyStats: List<DailySpendStat> = emptyList(),
        budgetOutcomes: List<MonthBudgetOutcome> = emptyList(),
        achievedChallengeCount: Int = 0,
    ) = AchievementInput(recordedDays, firstBillDate, dailyStats, budgetOutcomes, achievedChallengeCount)

    private fun byId(states: List<AchievementState>) = states.associateBy { it.id }

    @Test
    fun `空输入 15 枚全部锁定且 id 与徽章文件名逐字一致`() {
        val states = evaluateAchievements(input())
        val expectedIds = listOf(
            "record-first", "record-7", "record-30", "record-100", "record-365",
            "nospend-month-3", "nospend-month-8", "nospend-month-15",
            "nospend-total-30", "nospend-total-100",
            "budget-first", "budget-month", "budget-3months",
            "challenge-3", "challenge-10",
        )
        assertEquals(15, states.size)
        assertEquals(expectedIds, states.map { it.id })
        assertTrue(states.all { !it.unlocked })
        // 锁定态也给进度：全部 0/x
        assertTrue(states.all { it.progressCurrent == 0L })
        // 目标值抽查
        assertEquals(1L, byId(states)["record-first"]!!.progressTarget)
        assertEquals(365L, byId(states)["record-365"]!!.progressTarget)
        assertEquals(15L, byId(states)["nospend-month-15"]!!.progressTarget)
        assertEquals(100L, byId(states)["nospend-total-100"]!!.progressTarget)
        assertEquals(3L, byId(states)["budget-3months"]!!.progressTarget)
        assertEquals(10L, byId(states)["challenge-10"]!!.progressTarget)
    }

    @Test
    fun `record-first 首笔账解锁`() {
        val locked = byId(evaluateAchievements(input(firstBillDate = null)))["record-first"]!!
        assertFalse(locked.unlocked)
        assertEquals(0L, locked.progressCurrent)
        val unlocked = byId(evaluateAchievements(input(firstBillDate = epoch(LocalDate.of(2026, 1, 1)))))["record-first"]!!
        assertTrue(unlocked.unlocked)
        assertEquals(1L, unlocked.progressCurrent)
    }

    @Test
    fun `record 系列 累计记账天数进度封顶到目标`() {
        val states = byId(evaluateAchievements(input(recordedDays = 42)))
        assertTrue(states["record-7"]!!.unlocked)
        assertEquals(7L, states["record-7"]!!.progressCurrent) // 42 封顶到 7
        assertTrue(states["record-30"]!!.unlocked)
        assertEquals(30L, states["record-30"]!!.progressCurrent) // 42 封顶到 30
        assertFalse(states["record-100"]!!.unlocked)
        assertEquals(42L, states["record-100"]!!.progressCurrent)
        assertFalse(states["record-365"]!!.unlocked)
        assertEquals(42L, states["record-365"]!!.progressCurrent)
    }

    @Test
    fun `nospend-month 单月峰值 跨月归桶且只认有记账的零支出日`() {
        // 1/29 ~ 2/1 连续四天无消费，跨月：1 月桶 3 天、2 月桶 1 天（Asia/Shanghai 月界）
        val crossMonth = listOf(
            day(LocalDate.of(2026, 1, 29)),
            day(LocalDate.of(2026, 1, 30)),
            day(LocalDate.of(2026, 1, 31)),
            day(LocalDate.of(2026, 2, 1)),
        )
        // 2 月另有 8 天无消费 → 2 月峰值 9
        val february = (2..9).map { day(LocalDate.of(2026, 2, it)) }
        // 干扰项：有支出的一天 + 整天没记账的一天（都不计入无消费）
        val noise = listOf(
            day(LocalDate.of(2026, 1, 15), expenseMinor = 999),
            day(LocalDate.of(2026, 1, 16), billCount = 0),
        )
        val states = byId(evaluateAchievements(input(dailyStats = crossMonth + february + noise)))
        assertTrue(states["nospend-month-3"]!!.unlocked)
        assertTrue(states["nospend-month-8"]!!.unlocked)
        assertFalse(states["nospend-month-15"]!!.unlocked)
        assertEquals(3L, states["nospend-month-3"]!!.progressCurrent)
        assertEquals(8L, states["nospend-month-8"]!!.progressCurrent)
        assertEquals(9L, states["nospend-month-15"]!!.progressCurrent) // 峰值 9，未到目标不封顶
    }

    @Test
    fun `nospend-total 累计无消费天数`() {
        val stats = listOf(
            day(LocalDate.of(2026, 7, 1)),                     // 无消费
            day(LocalDate.of(2026, 7, 2), expenseMinor = 500), // 有支出，不计
            day(LocalDate.of(2026, 8, 1)),                     // 无消费
            day(LocalDate.of(2026, 8, 2), billCount = 0),      // 没记账，不计
            day(LocalDate.of(2026, 9, 1)),                     // 无消费
        )
        val states = byId(evaluateAchievements(input(dailyStats = stats)))
        assertFalse(states["nospend-total-30"]!!.unlocked)
        assertEquals(3L, states["nospend-total-30"]!!.progressCurrent)
        assertFalse(states["nospend-total-100"]!!.unlocked)
        assertEquals(3L, states["nospend-total-100"]!!.progressCurrent)
    }

    @Test
    fun `budget-first 设置过总额预算即解锁`() {
        val none = byId(evaluateAchievements(input()))["budget-first"]!!
        assertFalse(none.unlocked)
        assertEquals(0L, none.progressCurrent)
        // 有预算月即算（哪怕该月还没记账，expense = 0）
        val has = byId(
            evaluateAchievements(
                input(budgetOutcomes = listOf(MonthBudgetOutcome(epoch(LocalDate.of(2026, 9, 1)), 5000L, 0L))),
            )
        )["budget-first"]!!
        assertTrue(has.unlocked)
        assertEquals(1L, has.progressCurrent)
    }

    @Test
    fun `budget-month 恰好相等算不超支 超一分即不算`() {
        val sep = epoch(LocalDate.of(2026, 9, 1))
        val ok = byId(evaluateAchievements(input(budgetOutcomes = listOf(MonthBudgetOutcome(sep, 1000L, 1000L)))))["budget-month"]!!
        assertTrue(ok.unlocked) // expense == budget → 不超支
        assertEquals(1L, ok.progressCurrent)
        val over = byId(evaluateAchievements(input(budgetOutcomes = listOf(MonthBudgetOutcome(sep, 1000L, 1001L)))))["budget-month"]!!
        assertFalse(over.unlocked)
        assertEquals(0L, over.progressCurrent)
        // 有预算、整月零支出 → 不超支
        val idle = byId(evaluateAchievements(input(budgetOutcomes = listOf(MonthBudgetOutcome(sep, 1000L, 0L)))))["budget-month"]!!
        assertTrue(idle.unlocked)
    }

    @Test
    fun `budget-3months 连续三个月不超支 中间断一个月不算`() {
        fun monthOutcome(month: Int, expense: Long?, budget: Long = 1000L): MonthBudgetOutcome =
            // expense = null 模拟「该月未设预算」（budgetMinor = null）
            MonthBudgetOutcome(epoch(LocalDate.of(2026, month, 1)), if (expense == null) null else budget, expense ?: 0L)

        // 1/2/3 月连续不超支 → 解锁 3/3
        val run3 = byId(
            evaluateAchievements(input(budgetOutcomes = listOf(monthOutcome(1, 100L), monthOutcome(2, 0L), monthOutcome(3, 999L))))
        )["budget-3months"]!!
        assertTrue(run3.unlocked)
        assertEquals(3L, run3.progressCurrent)

        // 1/2/4 月：3 月空档 → 最长连续只有 2（中间断一个月不算）
        val gap = byId(
            evaluateAchievements(input(budgetOutcomes = listOf(monthOutcome(1, 100L), monthOutcome(2, 100L), monthOutcome(4, 100L))))
        )["budget-3months"]!!
        assertFalse(gap.unlocked)
        assertEquals(2L, gap.progressCurrent)

        // 2 月超支一分 → 断链，最长 1
        val broken = byId(
            evaluateAchievements(input(budgetOutcomes = listOf(monthOutcome(1, 100L), monthOutcome(2, 1001L), monthOutcome(3, 100L))))
        )["budget-3months"]!!
        assertFalse(broken.unlocked)
        assertEquals(1L, broken.progressCurrent)

        // 3 月未设预算（budgetMinor = null）→ 同样断链，最长 2
        val unbudgeted = byId(
            evaluateAchievements(
                input(budgetOutcomes = listOf(monthOutcome(1, 100L), monthOutcome(2, 100L), monthOutcome(3, null), monthOutcome(4, 100L)))
            )
        )["budget-3months"]!!
        assertFalse(unbudgeted.unlocked)
        assertEquals(2L, unbudgeted.progressCurrent)

        // 连续 4 个月 → 仍 3/3（封顶）
        val run4 = byId(evaluateAchievements(input(budgetOutcomes = (1..4).map { monthOutcome(it, 100L) })))["budget-3months"]!!
        assertTrue(run4.unlocked)
        assertEquals(3L, run4.progressCurrent)
    }

    @Test
    fun `challenge 系列 完成次数解锁`() {
        val states = byId(evaluateAchievements(input(achievedChallengeCount = 4)))
        assertTrue(states["challenge-3"]!!.unlocked)
        assertEquals(3L, states["challenge-3"]!!.progressCurrent) // 4 封顶到 3
        assertFalse(states["challenge-10"]!!.unlocked)
        assertEquals(4L, states["challenge-10"]!!.progressCurrent)
        val zero = byId(evaluateAchievements(input(achievedChallengeCount = 0)))
        assertFalse(zero["challenge-3"]!!.unlocked)
        assertEquals(0L, zero["challenge-3"]!!.progressCurrent)
    }

    @Test
    fun `全条件满足 15 枚全部解锁`() {
        // 2026-05-01 ~ 2026-08-31 共 123 天全部「有记账、零支出」→ 累计 123 ≥ 100、单月峰值 31 ≥ 15
        val noSpendDays = (0L..122L).map { day(LocalDate.of(2026, 5, 1).plusDays(it)) }
        val outcomes = listOf(
            MonthBudgetOutcome(epoch(LocalDate.of(2026, 7, 1)), 1000L, 100L),
            MonthBudgetOutcome(epoch(LocalDate.of(2026, 8, 1)), 1000L, 100L),
            MonthBudgetOutcome(epoch(LocalDate.of(2026, 9, 1)), 1000L, 100L),
        )
        val states = byId(
            evaluateAchievements(
                input(
                    recordedDays = 365,
                    firstBillDate = epoch(LocalDate.of(2025, 1, 1)),
                    dailyStats = noSpendDays,
                    budgetOutcomes = outcomes,
                    achievedChallengeCount = 10,
                )
            )
        )
        assertEquals(15, states.size)
        assertTrue(states.values.all { it.unlocked })
        assertTrue(states.values.all { it.progressCurrent == it.progressTarget })
    }
}
