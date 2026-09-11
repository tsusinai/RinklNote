package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiAssistServiceTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val llmParser = LLMParser(
        LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500)
    )
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val insightService = InsightService(llmParser, billService)
    private val service = AiAssistService(billService, budgetService, insightService)

    @Before
    fun setup() {
        TestDatabase.connect("aiassist")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, BudgetsTable
            )
            BillsTable.deleteAll(); BudgetsTable.deleteAll(); SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll(); CategoriesTable.deleteAll(); UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000061"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun todayStart(): Long =
        LocalDate.now(shanghai).atStartOfDay(shanghai).toInstant().toEpochMilli()

    private fun monthStart(): Long =
        LocalDate.now(shanghai).withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()

    private fun catId(name: String): Long = transaction {
        CategoriesTable.selectAll().where { CategoriesTable.name eq name }.single()[CategoriesTable.id]
    }

    private fun insertBill(amount: Double, categoryName: String, date: Long, billType: String = "EXPENSE"): Long =
        transaction {
            BillsTable.insert {
                it[BillsTable.userId] = 1L
                it[BillsTable.amountMinor] = Money.toMinor(amount)
                it[BillsTable.amount] = amount
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = catId(categoryName)
                it[BillsTable.categoryName] = categoryName
                it[BillsTable.accountId] = 1
                it[BillsTable.date] = date
                it[BillsTable.billSource] = "APP"
                it[BillsTable.createdAt] = date
                it[BillsTable.updatedAt] = date
            } get BillsTable.id
        }

    @Test
    fun `record creates an AI bill and replies`() {
        val r = service.record(1L, 2000L, "三餐", null)
        assertTrue(r.reply.contains("已记录"))
        assertTrue(r.reply.contains("三餐"))
        val src = transaction {
            BillsTable.selectAll().where { BillsTable.userId eq 1L }.single()[BillsTable.billSource]
        }
        assertEquals("AI", src)
    }

    @Test
    fun `today reports only today expenses`() {
        insertBill(30.0, "三餐", todayStart())
        insertBill(8.0, "交通", todayStart())
        insertBill(99.0, "三餐", todayStart() - 24L * 3600 * 1000) // yesterday
        val r = service.today(1L)
        assertEquals(38.0, r.expense, 0.001)
        assertEquals(2, r.count)
        assertTrue(r.reply.contains("38.00"))
    }

    @Test
    fun `month reports expense income and budget remaining`() {
        budgetService.upsert(1L, monthStart(), 100000L)
        insertBill(300.0, "三餐", monthStart())
        val r = service.month(1L)
        assertEquals(300.0, r.expense, 0.001)
        assertEquals(1000.0, r.budget!!, 0.001)
        assertEquals(700.0, r.remaining!!, 0.001)
    }

    @Test
    fun `balance sums all accounts`() {
        val r = service.balance(1L)
        assertEquals(0.0, r.total, 0.001)
        assertTrue(r.accounts.isNotEmpty())   // 微信/支付宝/无账户
        assertTrue(r.reply.contains("余额合计"))
    }

    @Test
    fun `summary merges review with anomaly and falls back with dummy llm`() = runBlocking {
        insertBill(500.0, "三餐", monthStart())
        val r = service.summary(1L, null)
        assertNotNull(r.summary)
        assertTrue(r.summary.isNotBlank())
    }
}