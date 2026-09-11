package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BudgetSummaryDTO
import com.example.rinklnote.server.services.TemplateDTO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容性回归：迁移到整数分后，响应里必须**同时**保留旧的「元」字段（amount / totalExpense / ...），
 * 否则已发布的旧客户端会因为字段缺失而反序列化失败。
 *
 * 风险点：Json 默认 encodeDefaults = false，若兼容字段是「带默认值的构造参数」且
 * 取值恰好等于默认值，kotlinx-serialization 会把它整个省略。
 */
class LegacyAmountFieldSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    @Test
    fun `CategoryAmount 兼容字段 amount 必须输出`() {
        val text = json.encodeToString(CategoryAmount(name = "三餐", amountMinor = 5797))
        println("CategoryAmount => $text")
        assertTrue("缺少 amountMinor: $text", text.contains("\"amountMinor\":5797"))
        assertTrue("缺少兼容字段 amount: $text", text.contains("\"amount\""))
        assertTrue("兼容字段换算错误: $text", text.contains("57.97"))
    }

    @Test
    fun `DailyReportResponse 兼容字段必须输出`() {
        val text = json.encodeToString(
            DailyReportResponse(
                date = "2026-09-11",
                totalExpenseMinor = 5797,
                totalIncomeMinor = 10000,
                expenseCategories = emptyList(),
                incomeCategories = emptyList(),
                billCount = 1,
                summary = "占位"
            )
        )
        println("DailyReportResponse => $text")
        assertTrue("缺少 totalExpense: $text", text.contains("\"totalExpense\""))
        assertTrue("缺少 totalIncome: $text", text.contains("\"totalIncome\""))
    }

    @Test
    fun `MonthlyAnomalyResponse 兼容字段必须输出`() {
        val text = json.encodeToString(
            MonthlyAnomalyResponse(
                month = "2026-09",
                totalExpenseMinor = 5797,
                activeDays = 1,
                avgDailyExpenseMinor = 5797,
                spikeDays = listOf(MonthlySpike(date = "2026-09-11", amountMinor = 5797, ratioPct = 10)),
                biggestSingle = SingleBill(amountMinor = 5797, categoryName = "三餐", date = "2026-09-11"),
                topCategories = listOf(CategoryAmount(name = "三餐", amountMinor = 5797)),
                analysis = "占位"
            )
        )
        println("MonthlyAnomalyResponse => $text")
        assertTrue("缺少 totalExpense: $text", text.contains("\"totalExpense\""))
        assertTrue("缺少 avgDailyExpense: $text", text.contains("\"avgDailyExpense\""))
    }

    @Test
    fun `BudgetSummaryDTO 取值为 0 时兼容字段仍必须输出`() {
        val text = json.encodeToString(
            BudgetSummaryDTO(
                periodStart = 0L,
                totalExpenseMinor = 0L,
                totalExpense = 0.0,
                categoryBudgets = emptyList(),
                subCategoryBudgets = emptyList(),
                lastMonthSurplus = null
            )
        )
        println("BudgetSummaryDTO => $text")
        assertTrue("缺少 totalExpense: $text", text.contains("\"totalExpense\""))
        assertTrue("缺少 lastMonthSurplus: $text", text.contains("\"lastMonthSurplus\""))
    }

    @Test
    fun `TemplateDTO 兼容字段必须输出`() {
        val text = json.encodeToString(
            TemplateDTO(
                id = 1L, label = "早餐",
                amountMinor = 5797L, amount = 57.97,
                categoryId = 1L, categoryName = "三餐", accountId = 1L
            )
        )
        println("TemplateDTO => $text")
        assertTrue("缺少 amountMinor: $text", text.contains("\"amountMinor\":5797"))
        assertTrue("缺少兼容字段 amount: $text", text.contains("\"amount\""))
    }
}
