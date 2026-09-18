package com.example.rinklnote.util

import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 年度账单聚合单测：月份归桶（业务时区）、年过滤、Top5 分类、结余与贺词分支。
 */
class AnnualStatsTest {

    private val zone: ZoneId = bookkeepingZone()

    /** 构造一笔指定日期的账单（date = 当日 0 点 epoch millis，与 bills.date 口径一致）。 */
    private fun bill(year: Int, month: Int, day: Int, amountMinor: Long, type: BillType = BillType.EXPENSE, category: String = "三餐"): Bill =
        Bill(
            amountMinor = amountMinor,
            billType = type,
            categoryId = 1,
            categoryName = category,
            accountId = 1,
            date = LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
        )

    @Test
    fun `按月归桶与年合计`() {
        val stats = aggregateAnnualStats(
            listOf(
                bill(2026, 1, 5, 1000),
                bill(2026, 1, 20, 500),
                bill(2026, 3, 2, 2000),
                bill(2026, 12, 31, 3000)
            ),
            2026
        )
        assertEquals(1500L, stats.monthlyExpenseMinor[0])
        assertEquals(0L, stats.monthlyExpenseMinor[1])
        assertEquals(2000L, stats.monthlyExpenseMinor[2])
        assertEquals(3000L, stats.monthlyExpenseMinor[11])
        assertEquals(6500L, stats.totalExpenseMinor)
        assertEquals(12, stats.monthlyExpenseMinor.size)
    }

    @Test
    fun `跨年账单被过滤`() {
        val stats = aggregateAnnualStats(
            listOf(bill(2025, 6, 1, 9999), bill(2027, 1, 1, 8888), bill(2026, 5, 5, 100)),
            2026
        )
        assertEquals(100L, stats.totalExpenseMinor)
    }

    @Test
    fun `收入只进收入列不进Top分类`() {
        val stats = aggregateAnnualStats(
            listOf(bill(2026, 2, 1, 5000, BillType.INCOME, "工资"), bill(2026, 2, 2, 1200)),
            2026
        )
        assertEquals(5000L, stats.totalIncomeMinor)
        assertEquals(1200L, stats.totalExpenseMinor)
        assertEquals(1, stats.topCategories.size)
        assertEquals("三餐", stats.topCategories[0].categoryName)
    }

    @Test
    fun `Top5按金额降序且最多五个`() {
        val bills = listOf(
            bill(2026, 1, 1, 100, category = "A"),
            bill(2026, 1, 1, 500, category = "B"),
            bill(2026, 1, 1, 300, category = "C"),
            bill(2026, 1, 1, 400, category = "D"),
            bill(2026, 1, 1, 200, category = "E"),
            bill(2026, 1, 1, 50, category = "F"),
            bill(2026, 1, 1, 10, category = "G")
        )
        val stats = aggregateAnnualStats(bills, 2026)
        val top = stats.topCategories
        assertEquals(5, top.size)
        assertEquals(listOf("B", "D", "C", "E", "A"), top.map { it.categoryName })
        assertEquals(500L, top[0].amountMinor)
    }

    @Test
    fun `结余口径为收入减支出`() {
        val stats = aggregateAnnualStats(
            listOf(bill(2026, 1, 1, 3000, BillType.INCOME), bill(2026, 1, 2, 1000)),
            2026
        )
        assertEquals(2000L, stats.netMinor)
    }

    @Test
    fun `贺词按数据分支`() {
        val empty = aggregateAnnualStats(emptyList(), 2026)
        assertEquals("2026 年还没开始记，小盘等你一起来！", annualGreeting(empty))

        val positive = aggregateAnnualStats(
            listOf(bill(2026, 1, 1, 3000, BillType.INCOME), bill(2026, 1, 2, 1000)),
            2026
        )
        assertTrue(annualGreeting(positive).contains("点赞"))

        val negative = aggregateAnnualStats(
            listOf(bill(2026, 1, 2, 3000)),
            2026
        )
        assertTrue(annualGreeting(negative).contains("小盘同行"))

        val balanced = aggregateAnnualStats(
            listOf(bill(2026, 1, 2, 1000), bill(2026, 1, 1, 1000, BillType.INCOME)),
            2026
        )
        assertTrue(annualGreeting(balanced).contains("稳稳"))
    }
}
