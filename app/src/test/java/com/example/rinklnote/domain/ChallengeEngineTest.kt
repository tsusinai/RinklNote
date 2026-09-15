package com.example.rinklnote.domain

import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.DailySpendStat
import com.example.rinklnote.util.bookkeepingZone
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ChallengeEngine 纯函数单测。
 * 全部用固定 LocalDate + 业务时区（Asia/Shanghai）构造 epoch，禁止依赖系统时钟，离线可跑。
 */
class ChallengeEngineTest {

    private val zone: ZoneId = bookkeepingZone()

    /** 固定日期 → 当日 0 点 epoch（业务时区），与 bills.date 分组键同口径。 */
    private fun epoch(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun day(date: LocalDate, billCount: Int = 1, expenseMinor: Long = 0L) =
        DailySpendStat(dayStart = epoch(date), billCount = billCount, expenseMinor = expenseMinor)

    private fun budget(
        monthStartEpoch: Long,
        amountMinor: Long,
        categoryId: Long? = null,
        subCategoryId: Long? = null,
        deleted: Boolean = false,
    ) = Budget(
        id = 0,
        serverId = null,
        monthStart = monthStartEpoch,
        periodType = "MONTHLY",
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        amountMinor = amountMinor,
        updatedAt = null,
        deleted = deleted,
        dirty = false,
    )

    @Test
    fun `dayKind 四种口径`() {
        assertEquals(DayKind.NO_RECORD, dayKind(null))
        assertEquals(DayKind.NO_RECORD, dayKind(day(LocalDate.of(2026, 9, 1), billCount = 0)))
        // 只记收入（有记账动作、零支出）= 无消费日
        assertEquals(DayKind.NO_SPEND, dayKind(day(LocalDate.of(2026, 9, 2), billCount = 2, expenseMinor = 0)))
        assertEquals(DayKind.SPEND, dayKind(day(LocalDate.of(2026, 9, 3), billCount = 1, expenseMinor = 1)))
    }

    @Test
    fun `noSpendDaysBetween 半开区间且只认无消费日`() {
        val d = LocalDate.of(2026, 9, 10)
        val stats = listOf(
            day(d, expenseMinor = 0),               // 无消费日
            day(d.plusDays(1), expenseMinor = 500), // 有支出，不计
            day(d.plusDays(2), billCount = 0),      // 整天没记账，不计
            day(d.plusDays(3), expenseMinor = 0),   // 无消费日，但落在终点之外
        )
        // 窗口 [d, d+3)：只含前三行，其中无消费日只有 d
        assertEquals(1, noSpendDaysBetween(stats, epoch(d), epoch(d.plusDays(3))))
        // 终点排他：扩到 d+4 才把 d+3 算进来
        assertEquals(2, noSpendDaysBetween(stats, epoch(d), epoch(d.plusDays(4))))
        // 窗口 [d+1, d+3)：只有 SPEND / NO_RECORD
        assertEquals(0, noSpendDaysBetween(stats, epoch(d.plusDays(1)), epoch(d.plusDays(3))))
    }

    @Test
    fun `23点59 与 0点01 的天归属 - 业务时区午夜即翻日`() {
        // 北京时间 2026-01-15 23:59:59.999 与 2026-01-16 00:00:00.000 分属两天（Asia/Shanghai = UTC+8）
        val lastMilli = Instant.parse("2026-01-15T15:59:59.999Z")
        val firstMilli = Instant.parse("2026-01-15T16:00:00.000Z")
        assertEquals(LocalDate.of(2026, 1, 15), lastMilli.atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(2026, 1, 16), firstMilli.atZone(zone).toLocalDate())

        // remainingDays 在同一午夜翻转：终点是 16 日 23:59:59.999 → 16 日仍在周期内（剩 1 天）
        val today = LocalDate.of(2026, 1, 15)
        assertEquals(1, remainingDays(Instant.parse("2026-01-16T15:59:59.999Z").toEpochMilli(), today))
        assertEquals(2, remainingDays(Instant.parse("2026-01-16T16:00:00.000Z").toEpochMilli(), today))

        // 窗口半开区间：[15日0点, 16日0点) 只含 15 日的行
        val stats = listOf(
            DailySpendStat(epoch(LocalDate.of(2026, 1, 15)), billCount = 1, expenseMinor = 800),
            DailySpendStat(epoch(LocalDate.of(2026, 1, 16)), billCount = 1, expenseMinor = 900),
        )
        assertEquals(800L, expenseBetween(stats, epoch(LocalDate.of(2026, 1, 15)), epoch(LocalDate.of(2026, 1, 16))))
        assertEquals(900L, expenseBetween(stats, epoch(LocalDate.of(2026, 1, 16)), epoch(LocalDate.of(2026, 1, 17))))
    }

    @Test
    fun `weekStartOf 以周一为周起点`() {
        // 锚点自检：2026-09-14 确为周一
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 9, 14).dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 14), weekStartOf(LocalDate.of(2026, 9, 16))) // 周三 → 本周一
        assertEquals(LocalDate.of(2026, 9, 7), weekStartOf(LocalDate.of(2026, 9, 13)))  // 周日 → 回退本周一
        assertEquals(LocalDate.of(2026, 9, 14), weekStartOf(LocalDate.of(2026, 9, 14))) // 周一 → 自身
        // 周一 0 点（Asia/Shanghai）= UTC 2026-09-13T16:00:00Z，绝对值校验换算无时区漂移
        assertEquals(Instant.parse("2026-09-13T16:00:00Z").toEpochMilli(), epoch(LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun `monthStartOf 与 monthEndExclusive 月挑战口径`() {
        assertEquals(LocalDate.of(2026, 2, 1), monthStartOf(LocalDate.of(2026, 2, 14)))
        assertEquals(LocalDate.of(2026, 3, 1), monthEndExclusive(LocalDate.of(2026, 2, 14)))
        // 闰年 2 月
        assertEquals(LocalDate.of(2028, 3, 1), monthEndExclusive(LocalDate.of(2028, 2, 29)))
        // 跨年
        assertEquals(LocalDate.of(2027, 1, 1), monthEndExclusive(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `remainingDays 含今天 已过期返回 0`() {
        val today = LocalDate.of(2026, 9, 15)
        // 终点 = 明天 0 点 → 还剩 1 天（含今天）
        assertEquals(1, remainingDays(epoch(today.plusDays(1)), today))
        // 周挑战：终点 = 下周一 0 点，今天周四 → 剩 4 天（周四五六日，不含终点日）
        assertEquals(4, remainingDays(epoch(LocalDate.of(2026, 9, 21)), LocalDate.of(2026, 9, 17)))
        // 终点 = 今天 0 点（周期昨天已结束）→ 0；更早 → 0（不返回负数）
        assertEquals(0, remainingDays(epoch(today), today))
        assertEquals(0, remainingDays(epoch(today.minusDays(3)), today))
    }

    @Test
    fun `currentBookkeepingStreak 今天没记按昨天活着算`() {
        val today = LocalDate.of(2026, 9, 15)
        // 今天记了 → 含今天
        assertEquals(2, currentBookkeepingStreak(listOf(day(today.minusDays(1)), day(today, expenseMinor = 300)), today))
        // 今天还没记 → 从昨天起算（昨与前天都记了，大前天没记）
        assertEquals(
            2,
            currentBookkeepingStreak(
                listOf(day(today.minusDays(3), billCount = 0), day(today.minusDays(2)), day(today.minusDays(1))),
                today,
            ),
        )
        // 今天和昨天都没记 → 0
        assertEquals(0, currentBookkeepingStreak(listOf(day(today.minusDays(2))), today))
        // 今天没记、只记了收入（NO_SPEND）的昨天也算活着
        assertEquals(
            1,
            currentBookkeepingStreak(listOf(day(today.minusDays(2), billCount = 0), day(today.minusDays(1))), today),
        )
        // 断签（隔一天）：昨天活着但前天没记，只数到昨天
        assertEquals(1, currentBookkeepingStreak(listOf(day(today.minusDays(3)), day(today.minusDays(1))), today))
        // 空数据 → 0
        assertEquals(0, currentBookkeepingStreak(emptyList(), today))
    }

    @Test
    fun `longestBookkeepingStreak 断签取最长段`() {
        val base = LocalDate.of(2026, 8, 1)
        val streak1 = (0L..2L).map { day(base.plusDays(it)) } // 8/1 ~ 8/3
        val streak2 = (4L..7L).map { day(base.plusDays(it)) } // 8/5 ~ 8/8（隔一天断签）
        assertEquals(4, longestBookkeepingStreak(streak1 + streak2))
        assertEquals(3, longestBookkeepingStreak(streak1))
        assertEquals(0, longestBookkeepingStreak(emptyList()))
        assertEquals(1, longestBookkeepingStreak(listOf(day(base))))
        // 只记收入的连续三天（NO_SPEND）也算活着
        val incomeOnly = (10L..12L).map { day(base.plusDays(it), expenseMinor = 0) }
        assertEquals(3, longestBookkeepingStreak(incomeOnly))
    }

    @Test
    fun `expenseBetween 周支出窗口`() {
        val monday = weekStartOf(LocalDate.of(2026, 9, 16)) // 2026-09-14（周一）
        val nextMonday = monday.plusDays(7)
        val stats = listOf(
            day(monday.minusDays(1), expenseMinor = 1000), // 上周日，窗口外
            day(monday, expenseMinor = 500),               // 本周一
            day(monday.plusDays(6), expenseMinor = 700),   // 本周日
            day(nextMonday, expenseMinor = 300),           // 下周一，终点排他不计
        )
        assertEquals(1200L, expenseBetween(stats, epoch(monday), epoch(nextMonday)))
        assertEquals(0L, expenseBetween(emptyList(), epoch(monday), epoch(nextMonday)))
    }

    @Test
    fun `forecastMonth 按日均外推且 HALF_UP 到分`() {
        assertEquals(3000L, forecastMonth(1000, 10, 30))
        assertEquals(0L, forecastMonth(0, 5, 30))
        // 月末最后一天：外推值 = 已花金额本身
        assertEquals(12345L, forecastMonth(12345, 30, 30))
        // 100×31÷3 = 1033.33… → 1033
        assertEquals(1033L, forecastMonth(100, 3, 31))
        // 50×4÷3 = 66.67 → 67（四舍五入进位）
        assertEquals(67L, forecastMonth(50, 3, 4))
        // 恰好 .5 进位：3×1÷2 = 1.5 → 2（HALF_UP）
        assertEquals(2L, forecastMonth(3, 2, 1))
    }

    @Test
    fun `lessBuySaving 10 与 100 边界及夹取`() {
        assertEquals(100L, lessBuySaving(1000, 10))   // 下界 10%
        assertEquals(1000L, lessBuySaving(1000, 100)) // 上界 100% = 全额
        assertEquals(0L, lessBuySaving(0, 50))
        // HALF_UP：999×10% = 99.9 → 100
        assertEquals(100L, lessBuySaving(999, 10))
        // 12345×25% = 3086.25 → 3086（不足半分不进位）
        assertEquals(3086L, lessBuySaving(12345, 25))
        // 越界夹取：5% → 10%、150% → 100%（防「节省额」超过品类总额）
        assertEquals(100L, lessBuySaving(1000, 5))
        assertEquals(1000L, lessBuySaving(1000, 150))
    }

    @Test
    fun `monthlyBudgetOutcomes 只取总额维度预算并合并同月多行`() {
        val jul = LocalDate.of(2026, 7, 1)
        val aug = LocalDate.of(2026, 8, 1)
        val sep = LocalDate.of(2026, 9, 1)
        val budgets = listOf(
            budget(epoch(jul), 5000),                      // 总额预算 → 计入
            budget(epoch(jul), 999, categoryId = 3L),      // 分类预算 → 剔除
            budget(epoch(jul), 888, subCategoryId = 7L),   // 子分类预算 → 剔除
            budget(epoch(aug), 2000, deleted = true),      // 软删 → 剔除
            budget(epoch(sep), 1000),                      // 9 月第一行
            budget(epoch(sep), 500),                       // 9 月第二行（异常重复）→ 合计 1500
        )
        val stats = listOf(
            day(LocalDate.of(2026, 7, 3), expenseMinor = 1200),
            day(LocalDate.of(2026, 7, 20), expenseMinor = 800),
            day(LocalDate.of(2026, 8, 5), expenseMinor = 300),              // 8 月有支出但预算被软删 → budgetMinor = null
            day(LocalDate.of(2026, 8, 9), expenseMinor = 0),                // 只记收入也归入 8 月
        )
        val outcomes = monthlyBudgetOutcomes(stats, budgets)

        assertEquals(
            listOf(
                MonthBudgetOutcome(epoch(jul), 5000L, 2000L),
                MonthBudgetOutcome(epoch(aug), null, 300L),
                MonthBudgetOutcome(epoch(sep), 1500L, 0L), // 预算月无账单 → expense 0 也产出
            ),
            outcomes,
        )
        // 月份键已归一化为当月 1 日 0 点，且按升序返回
        assertEquals(listOf(epoch(jul), epoch(aug), epoch(sep)), outcomes.map { it.monthStart })
    }
}
