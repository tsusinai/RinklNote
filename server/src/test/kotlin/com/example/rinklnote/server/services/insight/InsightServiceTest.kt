package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillDTO
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BotConfigTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InsightServiceTest {

    private val billService = BillService()
    // habitReminder 只用 billService + loadSuggestConfig，不调 LLM；构造只需一个可用实例。
    private val insight = InsightService(
        llmParser = LLMParser(LLMParserConfig(apiKey = "dummy")),
        billService = billService
    )
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Before
    fun setup() {
        TestDatabase.connect("insighttest")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, BotConfigTable)
            BillsTable.deleteAll(); SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll(); CategoriesTable.deleteAll(); UsersTable.deleteAll(); BotConfigTable.deleteAll()
        }
        billService.seedIfNeeded()
        insertUser(1L, "13800000021")
    }

    private fun insertUser(id: Long, phone: String) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.phone] = phone
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    /** start-of-day epoch ms in Shanghai. */
    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun insertBill(userId: Long, amount: Double, categoryName: String = "三餐", date: Long, billType: String = "EXPENSE"): Long =
        transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amountMinor] = Money.toMinor(amount)
                it[BillsTable.amount] = amount
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = 1
                it[BillsTable.categoryName] = categoryName
                it[BillsTable.accountId] = 1
                it[BillsTable.date] = date
                it[BillsTable.billSource] = "APP"
                it[BillsTable.createdAt] = date
                it[BillsTable.updatedAt] = date
            } get BillsTable.id
        }

    @Test
    fun `naturalQueryContext includes category+amount+date but not remark`() {
        val ctx = InsightService.naturalQueryContext(
            query = "8月花了多少",
            categories = listOf("三餐", "交通"),
            year = 2026,
            month = 8,
            totalExpense = 10000L,
            totalIncome = 5000L,
            topCategories = listOf("三餐" to 10000L),
            recentBills = listOf(
                BillDTO(id = 1, amountMinor = 2800L, amount = 28.0, billType = "EXPENSE", categoryId = 1,
                    categoryName = "三餐", subCategoryName = null, accountId = 1,
                    remark = "机密周二午餐", date = 1724169600000L, source = "app",
                    createdAt = 1724169600000L)
            )
        )
        assertTrue(ctx.contains("三餐"))
        assertTrue(ctx.contains("28.00"))
        assertTrue("应标注所问月份", ctx.contains("2026-8"))
        assertFalse("备注不应送 LLM", ctx.contains("机密"))
    }

    @Test
    fun `resolveYearMonth parses bare month number`() {
        val now = java.time.LocalDate.of(2026, 9, 1)
        assertEquals(java.time.LocalDate.of(2026, 8, 1), InsightService.resolveYearMonth("8月交通花了多少", now))
        assertEquals(java.time.LocalDate.of(2026, 8, 1), InsightService.resolveYearMonth("八月花了多少", now))
    }

    @Test
    fun `resolveYearMonth rolls future bare month to last year`() {
        val now = java.time.LocalDate.of(2026, 9, 1)
        assertEquals(java.time.LocalDate.of(2025, 12, 1), InsightService.resolveYearMonth("12月花了多少", now))
    }

    @Test
    fun `resolveYearMonth parses relative and explicit months`() {
        val now = java.time.LocalDate.of(2026, 9, 1)
        assertEquals(java.time.LocalDate.of(2026, 8, 1), InsightService.resolveYearMonth("上个月花了多少", now))
        assertEquals(java.time.LocalDate.of(2026, 9, 1), InsightService.resolveYearMonth("这个月花了多少", now))
        assertEquals(java.time.LocalDate.of(2025, 3, 1), InsightService.resolveYearMonth("2025-03", now))
        assertEquals(java.time.LocalDate.of(2025, 8, 1), InsightService.resolveYearMonth("去年8月", now))
    }

    @Test
    fun `habitReminder returns reminder when today not recorded`() {
        // 午餐时段 11-14；7 日回看窗口内 3 笔三餐28，今天无三餐 → 触达
        val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 25))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 26))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 27))

        val r = insight.habitReminder(1L, now)
        assertEquals("午餐", r!!.label)
        assertEquals("三餐", r.categoryName)
        assertEquals(28.0, r.amount, 0.0001)
    }

    @Test
    fun `habitReminder null when today already recorded that category`() {
        val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 25))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 26))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 27))
        // 今天已记三餐 → 不提醒
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 31))

        assertNull(insight.habitReminder(1L, now))
    }

    @Test
    fun `habitReminder null when below minOccurrences`() {
        val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 25))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 26))
        // 仅 2 笔 < minOccurrences(3) → null

        assertNull(insight.habitReminder(1L, now))
    }

    @Test
    fun `habitForApp returns content when habit present`() {
        val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 25))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 26))
        insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 27))
        // polishHabitCopy 用 dummy LLM 会失败 → 回退模板文案，content 仍非空且含分类
        val r = runBlocking { insight.habitForApp(1L, now) }
        assertNotNull(r.content)
        assertTrue(r.content!!.contains("三餐"))
    }

    @Test
    fun `dailyReport summary contains real amounts categories and bill count`() {
        val day = startOfDay(2026, 8, 31)
        insertBill(1L, 28.0, "三餐", day)
        insertBill(1L, 18.5, "交通", day)
        insertBill(1L, 3000.0, "工资", day, billType = "INCOME")

        val r = runBlocking { insight.dailyReport(1L, day, day + 86_400_000L) }

        assertTrue("应含支出合计 46.50", r.summary.contains("46.50"))
        assertTrue("应含分类 三餐", r.summary.contains("三餐"))
        assertTrue("应含分类 交通", r.summary.contains("交通"))
        assertTrue("应含收入 3000.00", r.summary.contains("3000.00"))
        assertTrue("应含笔数 3 笔", r.summary.contains("3 笔"))
        assertEquals(3, r.billCount)
    }

    @Test
    fun `habitForApp returns null content when no habit`() {
        val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
        val r = runBlocking { insight.habitForApp(1L, now) }
        assertNull(r.content)
    }
}
