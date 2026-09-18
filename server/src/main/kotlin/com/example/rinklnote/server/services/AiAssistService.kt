package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class AiRecordResponse(val reply: String)

@Serializable
data class AiTodayResponse(
    val reply: String,
    val expenseMinor: Long, val incomeMinor: Long,
    // 旧字段，仅供旧客户端。
    val expense: Double, val income: Double,
    val count: Int
)

@Serializable
data class AiMonthResponse(
    val reply: String,
    val expenseMinor: Long, val incomeMinor: Long,
    val budgetMinor: Long?, val remainingMinor: Long?,
    // 旧字段，仅供旧客户端。
    val expense: Double, val income: Double,
    val budget: Double?, val remaining: Double?
)

@Serializable
data class AiAccountBalance(
    val name: String,
    val balanceMinor: Long,
    // 旧字段，仅供旧客户端。
    val balance: Double
)

@Serializable
data class AiBalanceResponse(
    val reply: String,
    val accounts: List<AiAccountBalance>,
    val totalMinor: Long,
    // 旧字段，仅供旧客户端。
    val total: Double
)

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
    fun record(userId: Long, amountMinor: Long, category: String?, remark: String?): AiRecordResponse {
        require(amountMinor > 0) { "金额必须大于0" }
        val bill = billService.createBill(userId, amountMinor, category, remark, "AI")
        // 个人记忆层（2026-09-18 Task 1.2）：AI 落账出口同样异步累计聚合画像（写失败不影响落账）。
        UserMemoryService.recordBillAsync(userId, remark, bill.categoryName)
        return AiRecordResponse("已记录：${bill.categoryName} ¥${Money.format(bill.amountMinor)}")
    }

    fun today(userId: Long): AiTodayResponse {
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val bills = billService.allBills(userId).filter { it.date >= todayStart && it.date < todayEnd }
        val expenseBills = bills.filter { it.billType == "EXPENSE" }
        val expense = expenseBills.sumOf { it.amountMinor }
        val income = bills.filter { it.billType == "INCOME" }.sumOf { it.amountMinor }
        return AiTodayResponse(
            "今天已花 ¥${Money.format(expense)}（${expenseBills.size}笔），收入 ¥${Money.format(income)}",
            expenseMinor = expense, incomeMinor = income,
            expense = Money.fromMinor(expense), income = Money.fromMinor(income),
            count = expenseBills.size
        )
    }

    fun month(userId: Long): AiMonthResponse {
        val now = LocalDate.now(zone)
        val monthStart = now.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthStart = now.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        val budget = budgetService.list(userId).firstOrNull { it.monthStart == monthStart && !it.deleted }
        val remaining = budget?.let { it.amountMinor - stats.totalExpenseMinor }
        val reply = buildString {
            append("本月支出 ¥${Money.format(stats.totalExpenseMinor)}，收入 ¥${Money.format(stats.totalIncomeMinor)}")
            if (budget != null) append("，预算 ¥${Money.format(budget.amountMinor)}，剩余 ¥${Money.format(remaining!!)}")
        }
        return AiMonthResponse(
            reply,
            expenseMinor = stats.totalExpenseMinor, incomeMinor = stats.totalIncomeMinor,
            budgetMinor = budget?.amountMinor, remainingMinor = remaining,
            expense = Money.fromMinor(stats.totalExpenseMinor), income = Money.fromMinor(stats.totalIncomeMinor),
            budget = budget?.let { Money.fromMinor(it.amountMinor) }, remaining = remaining?.let { Money.fromMinor(it) }
        )
    }

    fun balance(userId: Long): AiBalanceResponse {
        val accounts = billService.accountsFor(userId)
        val total = accounts.sumOf { it.balanceMinor }
        val reply = if (accounts.isEmpty()) "还没有账户，先去 App 加一个吧～"
        else "账户余额合计 ¥${Money.format(total)}：\n" +
            accounts.joinToString("\n") { "${it.name} ¥${Money.format(it.balanceMinor)}" }
        return AiBalanceResponse(
            reply,
            accounts.map { AiAccountBalance(it.name, it.balanceMinor, Money.fromMinor(it.balanceMinor)) },
            totalMinor = total, total = Money.fromMinor(total)
        )
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