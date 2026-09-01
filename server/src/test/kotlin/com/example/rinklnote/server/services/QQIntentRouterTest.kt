package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-natural-language QQ bot intent routing. The real LLM is never reached:
 * the dummy LLM points at a closed port and returns null fast, so monthlySummary
 * and naturalQuery fall back to their deterministic copy.
 */
class QQIntentRouterTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val llmParser = LLMParser(
        LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500)
    )
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val insightService = InsightService(llmParser, billService)
    private val nlu = DefaultNLUService(RuleBasedParser(), llmParser, billService)
    private val router = QQIntentRouter(billService, budgetService, insightService, nlu)

    @Before
    fun setup() {
        TestDatabase.connect("qqrouter")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, BudgetsTable, VoiceKeywordsTable
            )
            VoiceKeywordsTable.deleteAll()
            BillsTable.deleteAll()
            BudgetsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000041"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun catId(name: String): Long = transaction {
        CategoriesTable.selectAll().where { CategoriesTable.name eq name }.single()[CategoriesTable.id]
    }

    private fun insertBill(userId: Long, amount: Double, categoryName: String, date: Long): Long = transaction {
        BillsTable.insert {
            it[BillsTable.userId] = userId
            it[BillsTable.amount] = amount
            it[BillsTable.billType] = "EXPENSE"
            it[BillsTable.categoryId] = catId(categoryName)
            it[BillsTable.categoryName] = categoryName
            it[BillsTable.accountId] = 1
            it[BillsTable.date] = date
            it[BillsTable.billSource] = "APP"
            it[BillsTable.createdAt] = date
            it[BillsTable.updatedAt] = date
        } get BillsTable.id
    }

    private fun todayStart(): Long =
        LocalDate.now(shanghai).atStartOfDay(shanghai).toInstant().toEpochMilli()

    private fun currentMonthStart(): Long =
        LocalDate.now(shanghai).withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()

    @Test
    fun `bookkeeping message is recorded and never treated as a query`() = runBlocking {
        val reply = router.route("午餐20元", 1L)
        assertTrue("reply=$reply", reply.contains("已记录"))
        assertTrue(reply.contains("三餐"))
        assertTrue(reply.contains("20.00"))
        // Exactly one bill was created.
        val count = transaction { BillsTable.selectAll().where { BillsTable.userId eq 1L }.count() }
        assertEquals(1L, count)
    }

    @Test
    fun `today query sums only today bills and creates no new bill`() = runBlocking {
        insertBill(1L, 30.0, "三餐", todayStart())
        insertBill(1L, 8.0, "交通", todayStart())
        insertBill(1L, 99.0, "三餐", todayStart() - 24L * 3600 * 1000) // yesterday

        val reply = router.route("今天花了多少", 1L)
        assertTrue("reply=$reply", reply.contains("38.00"))
        assertTrue(reply.contains("2笔"))
        val count = transaction { BillsTable.selectAll().where { BillsTable.userId eq 1L }.count() }
        assertEquals(3L, count)
    }

    @Test
    fun `amount-bearing sentence books, amount-free question queries the category`() = runBlocking {
        val booked = router.route("打车花了20", 1L)
        assertTrue("reply=$booked", booked.contains("已记录"))
        assertTrue(booked.contains("交通"))

        insertBill(1L, 320.0, "交通", currentMonthStart())

        val asked = router.route("打车花了多少", 1L)
        assertTrue("reply=$asked", asked.contains("交通"))
        // createBill (¥20, today) + the inserted ¥320 both fall in this month.
        assertTrue("reply=$asked", asked.contains("340.00"))
    }

    @Test
    fun `delete by category removes the newest matching bill`() = runBlocking {
        insertBill(1L, 10.0, "三餐", todayStart() - 3600 * 1000)
        insertBill(1L, 20.0, "交通", todayStart())
        insertBill(1L, 30.0, "三餐", todayStart())

        val reply = router.route("删除午餐", 1L)
        assertTrue("reply=$reply", reply.contains("已删除"))
        assertTrue(reply.contains("三餐"))
        assertTrue(reply.contains("30.00"))
        // deleteBill is a soft delete; allBills excludes soft-deleted rows.
        assertEquals(2, billService.allBills(1L).size)
    }

    @Test
    fun `delete by reference removes the newest bill`() = runBlocking {
        insertBill(1L, 10.0, "三餐", todayStart() - 3600 * 1000)
        insertBill(1L, 20.0, "交通", todayStart())

        val reply = router.route("删掉刚才那笔", 1L)
        assertTrue("reply=$reply", reply.contains("已删除"))
        assertTrue(reply.contains("交通"))
    }

    @Test
    fun `delete by amount suffix matches on cents`() = runBlocking {
        insertBill(1L, 12.0, "三餐", todayStart() - 3600 * 1000)
        insertBill(1L, 12.0, "交通", todayStart())
        insertBill(1L, 45.0, "娱乐", todayStart())

        val reply = router.route("删了12块", 1L)
        assertTrue("reply=$reply", reply.contains("已删除"))
        assertTrue(reply.contains("12.00"))
        assertEquals(2, billService.allBills(1L).size)
    }

    @Test
    fun `budget query reports spent and remaining`() = runBlocking {
        val monthStart = currentMonthStart()
        budgetService.upsert(1L, monthStart, 1000.0)
        insertBill(1L, 300.0, "三餐", monthStart)

        val reply = router.route("这个月预算", 1L)
        assertTrue("reply=$reply", reply.contains("1000.00"))
        assertTrue(reply.contains("已用 ¥300.00"))
        assertTrue(reply.contains("剩余 ¥700.00"))
    }

    @Test
    fun `category without amount asks for a number instead of going to LLM`() = runBlocking {
        val reply = router.route("午餐", 1L)
        assertTrue("reply=$reply", reply.contains("请补金额"))
    }

    @Test
    fun `unrecognised text falls back to LLM natural query`() = runBlocking {
        val reply = router.route("你好", 1L)
        assertTrue("reply=$reply", reply.isNotBlank())
    }

    @Test
    fun `bare greeting is answered not treated as bookkeeping`() = runBlocking {
        // Regression: previously "你好" fell through to NLU, which labelled it with a
        // catch-all category (「其他」) and the router replied 「请补金额」.
        val reply = router.route("你好", 1L)
        assertTrue("reply=$reply", reply.isNotBlank())
        assertTrue("reply=$reply", !reply.contains("请补金额"))
        assertFalse("reply=$reply", reply.contains("已记录"))
    }

    @Test
    fun `what-can-you-do triggers help not bookkeeping`() = runBlocking {
        val reply = router.route("你是干什么的", 1L)
        assertTrue("reply=$reply", reply.contains("记账小帮手"))
        assertTrue("reply=$reply", !reply.contains("请补金额"))
    }

    @Test
    fun `monthly summary falls back to deterministic copy with dummy LLM`() = runBlocking {
        insertBill(1L, 500.0, "三餐", currentMonthStart())
        val reply = router.route("分析一下这个月", 1L)
        assertTrue("reply=$reply", reply.contains("总支出"))
    }
}
