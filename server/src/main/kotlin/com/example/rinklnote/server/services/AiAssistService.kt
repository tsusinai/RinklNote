package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class AiRecordResponse(val reply: String)

@Serializable
data class AiTodayResponse(val reply: String, val expense: Double, val income: Double, val count: Int)

@Serializable
data class AiMonthResponse(
    val reply: String, val expense: Double, val income: Double,
    val budget: Double?, val remaining: Double?
)

@Serializable
data class AiAccountBalance(val name: String, val balance: Double)

@Serializable
data class AiBalanceResponse(val reply: String, val accounts: List<AiAccountBalance>, val total: Double)

@Serializable
data class AiSummaryResponse(val reply: String, val summary: String, val highlights: List<String>)

/**
 * 手机 AI（小爱等）的结构化副接口。纯计算单元：所有查询/记账复用
 * [BillService]/[BudgetService]/[InsightService]，无 HTTP 依赖，可直接单测。
 * 自然语言入口（/api/ai/ask）由 [PhoneIntentRouter] 处理；本类只服务结构化端点。
 */
class AiAssistService(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val insightService: InsightService,
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
) {
    fun record(userId: Long, amount: Double, category: String?, remark: String?): AiRecordResponse {
        require(amount > 0 && amount.isFinite()) { "金额必须大于0" }
        val bill = billService.createBill(userId, amount, category, remark, "AI")
        return AiRecordResponse("已记录：${bill.categoryName} ¥${"%.2f".format(bill.amount)}")
    }

    fun today(userId: Long): AiTodayResponse {
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val bills = billService.allBills(userId).filter { it.date >= todayStart && it.date < todayEnd }
        val expenseBills = bills.filter { it.billType == "EXPENSE" }
        val expense = Money.cents(expenseBills.sumOf { it.amount })
        val income = Money.cents(bills.filter { it.billType == "INCOME" }.sumOf { it.amount })
        return AiTodayResponse(
            "今天已花 ¥${"%.2f".format(expense)}（${expenseBills.size}笔），收入 ¥${"%.2f".format(income)}",
            expense, income, expenseBills.size
        )
    }

    fun month(userId: Long): AiMonthResponse {
        val now = LocalDate.now(zone)
        val monthStart = now.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthStart = now.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        val budget = budgetService.list(userId).firstOrNull { it.monthStart == monthStart && !it.deleted }
        val remaining = budget?.let { Money.cents(it.amount - stats.totalExpense) }
        val reply = buildString {
            append("本月支出 ¥${"%.2f".format(stats.totalExpense)}，收入 ¥${"%.2f".format(stats.totalIncome)}")
            if (budget != null) append("，预算 ¥${"%.2f".format(budget.amount)}，剩余 ¥${"%.2f".format(remaining!!)}")
        }
        return AiMonthResponse(reply, stats.totalExpense, stats.totalIncome, budget?.amount, remaining)
    }

    fun balance(userId: Long): AiBalanceResponse {
        val accounts = billService.accountsFor(userId)
        val total = Money.cents(accounts.sumOf { it.balance })
        val reply = if (accounts.isEmpty()) "还没有账户，先去 App 加一个吧～"
        else "账户余额合计 ¥${"%.2f".format(total)}：\n" +
            accounts.joinToString("\n") { "${it.name} ¥${"%.2f".format(it.balance)}" }
        return AiBalanceResponse(reply, accounts.map { AiAccountBalance(it.name, it.balance) }, total)
    }

    suspend fun summary(userId: Long, month: String?): AiSummaryResponse {
        val target = month ?: LocalDate.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val review = insightService.monthlyReview(userId, target)
        val alerts = insightService.anomalyCheck(userId).alerts
        val sb = StringBuilder(review.summary)
        review.highlights.forEach { sb.append("\n· ").append(it) }
        alerts.forEach { sb.append("\n⚠️ ").append(it.message) }
        return AiSummaryResponse(sb.toString(), review.summary, review.highlights)
    }
}