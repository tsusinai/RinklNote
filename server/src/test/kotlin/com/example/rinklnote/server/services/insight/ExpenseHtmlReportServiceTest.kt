package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.tables.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpenseHtmlReportServiceTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Before fun setup() {
        TestDatabase.connect("htmlreporttest")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable)
            BillsTable.deleteAll(); SubCategoriesTable.deleteAll(); AccountsTable.deleteAll(); CategoriesTable.deleteAll(); UsersTable.deleteAll()
            for (user in 1L..2L) UsersTable.insert {
                it[id] = user; it[phone] = "1380000040$user"; it[passwordHash] = "hash"; it[createdAt] = "2026-01-01"
            }
        }
        BillService().seedIfNeeded()
        insert(1, 100, "2026-09-01")
        insert(1, 200, "2026-09-01")
        insert(1, 2000, "2026-09-02")
        insert(1, 777, "2026-09-02", deleted = true)
        insert(1, 200000, "2026-09-02", type = "INCOME")
        insert(1, 100000, "2026-10-02")
        insert(2, 600000, "2026-09-02")
    }
    private fun insert(user: Long, amount: Long, date: String, deleted: Boolean = false, type: String = "EXPENSE") {
        val timestamp = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
        transaction { BillsTable.insert {
            it[userId] = user; it[amountMinor] = amount; it[BillsTable.amount] = com.example.rinklnote.server.services.Money.fromMinor(amount)
            it[categoryId] = 1; it[categoryName] = "三餐"; it[accountId] = 1
            it[billType] = type; it[remark] = "SECRET_PRIVATE_REMARK"; it[BillsTable.date] = timestamp
            it[billSource] = "APP"; it[createdAt] = timestamp; it[updatedAt] = timestamp; it[BillsTable.deleted] = deleted
        } }
    }
    @Test fun `三种报告调用模型并只发送当前用户月份聚合`() = runBlocking {
        val prompts = mutableListOf<String>()
        val service = ExpenseHtmlReportService { _, prompt ->
            prompts += prompt
            """{"html":"<section><h2>AI观察</h2><p>第二日支出波动较大。</p><script>bad()</script></section>"}"""
        }
        for (type in listOf("analysis", "summary", "anomaly")) {
            val report = service.create(1, ExpenseReportRequest(type, "2026-09", "SECRET_RAW_QUERY"))
            assertEquals("llm", report.source)
            assertEquals(2300L, report.totalExpenseMinor)
            assertEquals(3L, report.expenseCount)
            assertTrue(report.hasAnomaly)
            assertTrue(report.html.contains("AI观察"))
            assertFalse(report.html.contains("script"))
        }
        assertEquals(3, prompts.size)
        assertTrue(prompts.all { it.contains("¥23.00") && it.contains("¥11.50") && it.contains("2026-09-02") })
        assertTrue(prompts.none { it.contains("SECRET") || it.contains("6000.00") || it.contains("2000.00") || it.contains("7.77") })
    }
    @Test fun `模型失败明确返回基础报告且转义分类`() = runBlocking {
        transaction { BillsTable.update({ BillsTable.userId eq 1L }) { it[categoryName] = "餐饮<script>" } }
        val report = ExpenseHtmlReportService { _, _ -> null }.create(1, ExpenseReportRequest("summary", "2026-09"))
        assertEquals("fallback", report.source)
        assertTrue(report.html.contains("&lt;script&gt;"))
        assertTrue(report.plainText.contains("基础报告"))
    }
    @Test fun `模型生成的完整页面保留CSS与账单图表`() = runBlocking {
        val page = """<!doctype html><html><head><style>.chart{padding:12px;background:var(--panel)}</style></head><body><main><h1>支出分析</h1><p>共 ¥23.00</p><svg class="chart" viewBox="0 0 100 40"><title>趋势</title><polyline points="0,30 100,5" fill="none" stroke="var(--primary)" /></svg></main></body></html>"""
        val json = kotlinx.serialization.json.buildJsonObject { put("html", kotlinx.serialization.json.JsonPrimitive(page)) }.toString()
        val report = ExpenseHtmlReportService { _, prompt ->
            assertTrue(prompt.contains("完整 HTML")); assertTrue(prompt.contains("静态 SVG"))
            json
        }.create(1, ExpenseReportRequest("analysis", "2026-09"))
        assertEquals("llm", report.source)
        assertTrue(report.html.contains("<style>")); assertTrue(report.html.contains("<svg"))
        assertTrue(report.html.contains("#llm-report .chart")); assertFalse(report.plainText.contains("padding"))
    }
    @Test fun `格式错误或纯脚本输出降级不假称AI生成`() = runBlocking {
        for (output in listOf("不是JSON", """{"html":"<script>bad()</script>"}""", """{"html":"<p>未闭合"}""")) {
            val report = ExpenseHtmlReportService { _, _ -> output }.create(1, ExpenseReportRequest("analysis", "2026-09"))
            assertEquals("fallback", report.source)
        }
    }
    @Test fun `空月份不请求模型`() = runBlocking {
        var calls = 0
        val report = ExpenseHtmlReportService { _, _ -> calls++; null }.create(1, ExpenseReportRequest("summary", "2026-07"))
        assertEquals(0, calls); assertEquals(0L, report.totalExpenseMinor)
        assertTrue(report.html.contains("暂无支出"))
    }
    @Test fun `自动异常只针对当天而手动报告可查看历史异常`() = runBlocking {
        var calls = 0
        val service = ExpenseHtmlReportService { _, _ -> calls++; null }
        val report = service.create(1, ExpenseReportRequest("anomaly", "2026-09", onlyIfAnomalous = true))
        assertFalse(report.hasAnomaly); assertEquals(0, calls)
    }
    @Test(expected = IllegalArgumentException::class)
    fun `错误类型拒绝`() = runBlocking { ExpenseHtmlReportService { _, _ -> null }.create(1, ExpenseReportRequest("unknown", "2026-09")); Unit }
    @Test(expected = CancellationException::class)
    fun `取消请求不会转换成成功的基础报告`() = runBlocking {
        ExpenseHtmlReportService { _, _ -> throw CancellationException() }.create(1, ExpenseReportRequest("summary", "2026-09")); Unit
    }
}
