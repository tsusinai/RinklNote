package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.tables.BotConfigTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.LocalTime
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
        // Validate month format
        require(month.matches(Regex("""^\d{4}-(0[1-9]|1[0-2])$"""))) { "月份格式错误，需要 YYYY-MM" }
        val bills = transaction { billService.syncBills(userId, null).bills }

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

        // Build real data context for the LLM
        val shanghai = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.now(shanghai)

        // Current month summary
        val monthStart = now.withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val monthBills = bills.filter { it.date >= monthStart }
        val totalExpense = monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }
        val topCategories = monthBills.filter { it.billType == "EXPENSE" }
            .groupBy { it.categoryName }
            .mapValues { it.value.sumOf { b -> b.amount } }
            .entries.sortedByDescending { it.value }.take(5)

        // Last month summary
        val lastMonthStart = now.minusMonths(1).withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val lastMonthBills = bills.filter { it.date in lastMonthStart until monthStart }
        val lastMonthExpense = lastMonthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }

        // Last 10 bills
        val recentBills = bills.sortedByDescending { it.date }.take(10)
            .joinToString("\n") { "- ${formatDate(it.date)} ${it.categoryName} ¥${"%.2f".format(it.amount)} ${it.remark ?: ""}" }

        val context = """
用户问题: "$query"

可用分类: ${categories.joinToString("、")}

数据:
- 当前月份: ${now.year}-${now.monthValue}
- 当月总支出: ¥${"%.2f".format(totalExpense)}
- 上月总支出: ¥${"%.2f".format(lastMonthExpense)}
- 当月消费TOP5: ${topCategories.joinToString { "${it.key} ¥${"%.2f".format(it.value)}" }}

最近10笔记录:
$recentBills

请仅基于以上真实数据回答用户的问题，不要编造数据。
如果是金额类问题，直接基于数据计算；如果数据不足以回答，明确告诉用户。
返回JSON: {"answer": "你的回答"}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个个人财务查询助手，用中文回答。你必须返回一个 JSON 对象: {\"answer\": \"你的回答\"}。只基于提供的数据回答，不要编造。",
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

    private fun formatDate(epochMs: Long): String {
        val d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.of("Asia/Shanghai"))
        return "${d.monthValue}/${d.dayOfMonth}"
    }

    // ── Smart Suggestion ──

    data class SuggestConfig(
        val enabled: Boolean = true,
        val lookbackDays: Int = 7,
        val minOccurrences: Int = 3,
        val displayDuration: Int = 5000,
        val timeWindows: List<TimeWindow> = listOf(
            TimeWindow("早餐", 7, 10),
            TimeWindow("午餐", 11, 14),
            TimeWindow("晚餐", 17, 20)
        )
    )

    data class TimeWindow(val label: String, val startHour: Int, val endHour: Int)

    fun suggestDailyPattern(userId: Long): Map<String, String>? {
        val bills = transaction { billService.syncBills(userId, null).bills }
        val config = loadSuggestConfig()
        if (!config.enabled) return null

        val now = LocalTime.now(ZoneId.of("Asia/Shanghai"))
        val hour = now.hour
        val window = config.timeWindows.find { hour in it.startHour until it.endHour } ?: return null

        val lookbackStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .minusDays(config.lookbackDays.toLong())
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val recent = bills.filter { it.date >= lookbackStart && it.billType == "EXPENSE" }

        if (recent.isEmpty()) return null

        val grouped = recent.groupBy { Pair(it.categoryName, it.amount) }
        val best = grouped.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < config.minOccurrences) return null

        return mapOf(
            "label" to window.label,
            "categoryName" to best.key.first,
            "amount" to best.key.second.toString()
        )
    }

    fun loadSuggestConfig(): SuggestConfig {
        return try {
            transaction {
                val row = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq "suggest_config" }.singleOrNull()
                if (row != null) Json { ignoreUnknownKeys = true }.decodeFromString(row[BotConfigTable.value])
                else SuggestConfig()
            }
        } catch (_: Exception) { SuggestConfig() }
    }

    fun saveSuggestConfig(config: SuggestConfig) {
        val jsonConfig = Json { encodeDefaults = true }.encodeToString(config)
        transaction {
            val exists = BotConfigTable.selectAll()
                .where { BotConfigTable.key eq "suggest_config" }.singleOrNull()
            if (exists != null) {
                BotConfigTable.update({ BotConfigTable.key eq "suggest_config" }) { it[value] = jsonConfig }
            } else {
                BotConfigTable.insert { it[key] = "suggest_config"; it[value] = jsonConfig }
            }
        }
    }
}
