package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillDTO
import com.example.rinklnote.server.services.BillService
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            query = "最近花了多少",
            categories = listOf("三餐", "交通"),
            now = java.time.LocalDate.of(2026, 8, 20),
            totalExpense = 100.0,
            totalIncome = 0.0,
            topCategories = listOf("三餐" to 100.0),
            recentBills = listOf(
                BillDTO(id = 1, amount = 28.0, billType = "EXPENSE", categoryId = 1,
                    categoryName = "三餐", subCategoryName = null, accountId = 1,
                    remark = "机密周二午餐", date = 1724169600000L, source = "app",
                    createdAt = 1724169600000L)
            )
        )
        assertTrue(ctx.contains("三餐"))
        assertTrue(ctx.contains("28.00"))
        assertFalse("备注不应送 LLM", ctx.contains("机密"))
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
}
