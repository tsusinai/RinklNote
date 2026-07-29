package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.nlu.LLMParser
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class MonthlySummaryResponse(
    val summary: String,
    val highlights: List<String>
)

@Serializable
data class AnomalyResponse(
    val alerts: List<AnomalyAlert>
)

@Serializable
data class AnomalyAlert(
    val level: String,
    val message: String,
    val type: String
)

@Serializable
data class QueryResponse(
    val answer: String
)

class InsightService(
    private val llmParser: LLMParser,
    private val billService: BillService,
    private val anomalyThreshold: Double = 1.5
) {
    suspend fun monthlySummary(userId: Long, month: String): MonthlySummaryResponse {
        val bills = transaction { billService.syncBills(userId, null).bills }

        // Filter bills for the target month
        val yearMonth = month.split("-")
        val targetYear = yearMonth[0].toInt()
        val targetMonth = yearMonth[1].toInt()

        val monthBills = bills.filter {
            val d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it.date), ZoneId.of("Asia/Shanghai"))
            d.year == targetYear && d.monthValue == targetMonth
        }

        val totalExpense = monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }
        val totalIncome = monthBills.filter { it.billType == "INCOME" }.sumOf { it.amount }
        val byCategory = monthBills.filter { it.billType == "EXPENSE" }
            .groupBy { it.categoryName }
            .mapValues { it.value.sumOf { b -> b.amount } }
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val context = """
账单数据 ($month):
- 总支出: ¥${"%.2f".format(totalExpense)}
- 总收入: ¥${"%.2f".format(totalIncome)}
- 支出分类TOP5: ${byCategory.joinToString { "${it.key} ¥${"%.2f".format(it.value)}" }}

请你用中文写一段简洁的月度消费总结（80-150字），并列出2-3个值得关注的点(highlights)。

返回JSON: {"summary": "总结文字", "highlights": ["亮点1", "亮点2"]}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个个人财务分析助手，用中文回答。你必须返回一个 JSON 对象。",
                context
            )
            if (jsonStr != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<MonthlySummaryResponse>(jsonStr)
                return parsed
            }
        } catch (_: Exception) {}

        return MonthlySummaryResponse(
            summary = "${month} 总支出 ¥${"%.2f".format(totalExpense)}，收入 ¥${"%.2f".format(totalIncome)}",
            highlights = emptyList()
        )
    }

    suspend fun anomalyCheck(userId: Long): AnomalyResponse {
        val bills = transaction { billService.syncBills(userId, null).bills }
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000

        val recentExpenses = bills.filter { it.billType == "EXPENSE" && it.date >= thirtyDaysAgo }
        val dailyAvg = recentExpenses.sumOf { it.amount } / 30.0

        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val todayExpense = bills.filter { it.billType == "EXPENSE" && it.date >= todayStart }
            .sumOf { it.amount }

        val alerts = mutableListOf<AnomalyAlert>()

        if (dailyAvg > 0 && todayExpense > dailyAvg * anomalyThreshold) {
            val pct = ((todayExpense / dailyAvg - 1) * 100).toInt()
            alerts.add(AnomalyAlert(
                level = "WARN",
                message = "今天支出 ¥${"%.2f".format(todayExpense)}，超出日均 ¥${"%.2f".format(dailyAvg)} 的 $pct%",
                type = "DAILY_SPIKE"
            ))
        }

        return AnomalyResponse(alerts = alerts)
    }

    suspend fun naturalQuery(userId: Long, query: String): QueryResponse {
        val bills = transaction { billService.syncBills(userId, null).bills }
        val categories = transaction { billService.getCategories().map { it.name } }

        // Brief summary for LLM context
        val monthStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .withDayOfMonth(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val monthBills = bills.filter { it.date >= monthStart }
        val totalExpense = monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }

        val context = """
用户问题: "$query"

当前可用分类: ${categories.joinToString("、")}
当月总支出: ¥${"%.2f".format(totalExpense)}

请根据问题类型直接回答（你可以假设你有数据）:
1. 如果是查金额→直接算出金额回答
2. 如果是问建议→直接回答
3. 否则→返回最合适的回答

返回JSON: {"answer": "你的回答"}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个个人财务查询助手，用中文回答。你必须返回一个 JSON 对象: {\"answer\": \"你的回答\"}",
                context
            )
            if (jsonStr != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<QueryResponse>(jsonStr)
                return parsed
            }
        } catch (_: Exception) {}

        return QueryResponse(answer = "抱歉，暂时无法理解这个问题。请尝试更具体的提问，如「上个月交通支出多少？」")
    }
}
