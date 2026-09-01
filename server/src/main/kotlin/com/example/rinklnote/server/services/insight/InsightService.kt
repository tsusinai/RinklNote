package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillDTO
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
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
import java.time.ZonedDateTime

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

@Serializable
data class HabitResponse(
    val content: String? = null
)

class InsightService(
    private val llmParser: LLMParser,
    private val billService: BillService,
    private val anomalyThreshold: Double = 1.5
) {
    suspend fun monthlySummary(userId: Long, month: String): MonthlySummaryResponse {
        // Validate month format
        require(month.matches(Regex("""^\d{4}-(0[1-9]|1[0-2])$"""))) { "月份格式错误，需要 YYYY-MM" }

        val yearMonth = month.split("-")
        val targetYear = yearMonth[0].toInt()
        val targetMonth = yearMonth[1].toInt()
        val shanghai = ZoneId.of("Asia/Shanghai")
        val monthStart = LocalDate.of(targetYear, targetMonth, 1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val nextMonthStart = LocalDate.of(targetYear, targetMonth, 1)
            .plusMonths(1).atStartOfDay(shanghai).toInstant().toEpochMilli()

        // SQL aggregate — no loading of all bills for a single month.
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        val totalExpense = stats.totalExpense
        val totalIncome = stats.totalIncome
        val byCategory = stats.topExpenseCategories

        val context = """
账单数据 ($month):
- 总支出: ¥${"%.2f".format(totalExpense)}
- 总收入: ¥${"%.2f".format(totalIncome)}
- 支出分类TOP5: ${byCategory.joinToString { "${it.first} ¥${"%.2f".format(it.second)}" }}

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
        val bills = billService.allBills(userId)
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000

        val recentExpenses = bills.filter { it.billType == "EXPENSE" && it.date >= thirtyDaysAgo }
        val dailyAvg = Money.cents(recentExpenses.sumOf { it.amount } / 30.0)

        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val todayExpense = Money.cents(bills.filter { it.billType == "EXPENSE" && it.date >= todayStart }
            .sumOf { it.amount })

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

    /**
     * 自然问账：先解析出用户提到的月份（如「8月/八月/上个月/2026-03」），再只把该月窗口内的
     * 聚合数据喂给 LLM。此前不管问哪个历史月，都只送「本月聚合 + 上月一个总和」，所以「8月交通花了多少」
     * 之类的问题无从回答。数据仍只含「分类+金额+日期」，不含备注与未聚合明细（NFR1）。
     */
    suspend fun naturalQuery(userId: Long, query: String): QueryResponse {
        val bills = billService.allBills(userId)
        val categories = transaction { billService.getCategories().map { it.name } }

        // 解析用户问的目标月份；未明确（如「最近花了多少」）则回落到本月首日。
        val target = resolveYearMonth(query, LocalDate.now(SHANGHAI)) ?: LocalDate.now(SHANGHAI).withDayOfMonth(1)

        val monthStart = target.atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
        val nextMonthStart = target.plusMonths(1).atStartOfDay(SHANGHAI).toInstant().toEpochMilli()

        // 只取目标月份窗口内的账单，避免跨月数据污染回答。
        val monthBills = bills.filter { it.date >= monthStart && it.date < nextMonthStart }

        val totalExpense = Money.cents(monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount })
        val totalIncome = Money.cents(monthBills.filter { it.billType == "INCOME" }.sumOf { it.amount })
        val topCategories = monthBills.filter { it.billType == "EXPENSE" }
            .groupBy { it.categoryName }
            .mapValues { Money.cents(it.value.sumOf { b -> b.amount }) }
            .entries.sortedByDescending { it.value }.take(5)
            .map { it.key to it.value }
        val recentBills = monthBills.sortedByDescending { it.date }.take(10)

        val context = naturalQueryContext(
            query = query,
            categories = categories,
            year = target.year,
            month = target.monthValue,
            totalExpense = totalExpense,
            totalIncome = totalIncome,
            topCategories = topCategories,
            recentBills = recentBills
        )

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
        val bills = billService.allBills(userId)
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

    data class HabitReminder(val label: String, val categoryName: String, val amount: Double)

    /**
     * FR6 习惯提醒：命中当前时段 + 回看窗口内某分类频率 ≥ minOccurrences 且今日未记该分类。
     * 账单只有日期粒度（无小时），因此「今日未记」用「今天该分类无 EXPENSE」近似「该时段未记」。
     */
    fun habitReminder(userId: Long, now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): HabitReminder? {
        val config = loadSuggestConfig()
        if (!config.enabled) return null
        val hour = now.hour
        val window = config.timeWindows.find { hour in it.startHour until it.endHour } ?: return null

        val todayStart = now.toLocalDate().atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
        val bills = billService.allBills(userId)

        val lookbackStart = now.toLocalDate().minusDays(config.lookbackDays.toLong())
            .atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
        val recent = bills.filter { it.billType == "EXPENSE" && it.date >= lookbackStart && it.date < todayStart }
        if (recent.isEmpty()) return null

        val grouped = recent.groupBy { Pair(it.categoryName, it.amount) }
        val best = grouped.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < config.minOccurrences) return null
        // 今日已记该分类则不提醒
        if (bills.any { it.billType == "EXPENSE" && it.date >= todayStart && it.categoryName == best.key.first }) return null

        return HabitReminder(window.label, best.key.first, best.key.second)
    }

    /** LLM 润色习惯提醒文案，只喂聚合（无明细/备注），失败回退模板。 */
    suspend fun polishHabitCopy(habit: HabitReminder): String {
        val context = """
习惯提醒线索（聚合，无任何明细/备注）:
- 时段: ${habit.label}
- 常记分类: ${habit.categoryName}
- 常记金额: ¥${"%.2f".format(habit.amount)}
请用一句话（≤30字）给出自然亲切、不硬性的提醒文案，例如「${habit.label}时段你常点 ${habit.categoryName} ¥${"%.2f".format(habit.amount)}，记得记一笔吗？」
返回JSON: {"answer": "..."}
""".trimIndent()
        return try {
            val jsonStr = llmParser.chat("你是一个贴心的记账提醒助手，用中文。必须返回 JSON 对象。", context)
            val parsed = jsonStr?.let { Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<QueryResponse>(it) }
            val ans = parsed?.answer?.trim()
            if (!ans.isNullOrBlank()) ans
            else "「${habit.label}」你常记 ${habit.categoryName} ¥${"%.2f".format(habit.amount)}，今天记了吗？"
        } catch (_: Exception) {
            "「${habit.label}」你常记 ${habit.categoryName} ¥${"%.2f".format(habit.amount)}，今天记了吗？"
        }
    }

    /** App 端习惯提醒：无习惯 → null；有习惯 → 已润色文案。与 QQ 端算法/文案一致。 */
    suspend fun habitForApp(userId: Long, now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): HabitResponse {
        val habit = habitReminder(userId, now)
        if (habit == null) return HabitResponse(null)
        return HabitResponse(polishHabitCopy(habit))
    }

    companion object {
        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

        private val CN_MONTH = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
            "七" to 7, "八" to 8, "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
        )

        /**
         * 从自然语言里解析出用户想查的月份，返回该月首日；解析不到返回 null。
         * 覆盖「8月 / 08月 / 八月 / 上个月 / 这个月 / 去年8月 / 2026-08 / 2026年3月」。
         * 裸月份数字（未给年份）落在当前年份；若该日期晚于今天（如 9 月问「12月」）则滚回上一年。
         * 纯函数，便于测试。
         */
        fun resolveYearMonth(query: String, now: LocalDate): LocalDate? {
            val text = query.replace(" ", "")

            if (text.contains("上个月") || text.contains("上月") || text.contains("上一月")) return now.minusMonths(1).withDayOfMonth(1)
            if (text.contains("这个月") || text.contains("本月") || text.contains("这月")) return now.withDayOfMonth(1)

            val lastYear = text.contains("去年")

            // 显式「年份+月份」：2026-08 / 2026年8月
            Regex("""(\d{4})[-年](\d{1,2})月?""").find(text)?.let { m ->
                val y = m.groupValues[1].toInt()
                val mm = m.groupValues[2].toIntOrNull()
                if (mm != null && mm in 1..12) return LocalDate.of(y, mm, 1)
            }

            // 裸月份：8月 / 08月 / 八月
            val bare = Regex("""(\d{1,2})月""").find(text)
                ?: Regex("""([一二三四五六七八九十]{1,2})月""").find(text)
            if (bare != null) {
                val mm = parseMonthNum(bare.groupValues[1]) ?: return null
                var y = if (lastYear) now.year - 1 else now.year
                var candidate = LocalDate.of(y, mm, 1)
                if (!lastYear && candidate.isAfter(now)) {
                    y -= 1
                    candidate = LocalDate.of(y, mm, 1)
                }
                return candidate
            }

            if (lastYear) return now.minusYears(1).withDayOfMonth(1)
            return null
        }

        private fun parseMonthNum(token: String): Int? {
            token.toIntOrNull()?.let { return if (it in 1..12) it else null }
            return CN_MONTH[token]
        }

        /**
         * 构造自然问账上下文。只送「分类 + 金额 + 日期」聚合，绝不包含备注或未聚合明细（NFR1）。
         * 纯函数，测试零依赖。year/month 指出上下文对应的数据区间（即用户所问的月份）。
         */
        fun naturalQueryContext(
            query: String,
            categories: List<String>,
            year: Int,
            month: Int,
            totalExpense: Double,
            totalIncome: Double,
            topCategories: List<Pair<String, Double>>,
            recentBills: List<BillDTO>
        ): String {
            val recentLines = recentBills.sortedByDescending { it.date }.take(10)
                .joinToString("\n") { "- ${formatDate(it.date)} ${it.categoryName} ¥${"%.2f".format(it.amount)}" }
            return """
用户问题: "$query"

可用分类: ${categories.joinToString("、")}

数据 ($year-$month):
- 月份: ${year}-${month}
- 总支出: ¥${"%.2f".format(totalExpense)}
- 总收入: ¥${"%.2f".format(totalIncome)}
- 支出分类TOP5: ${topCategories.joinToString { pair -> "${pair.first} ¥${"%.2f".format(pair.second)}" }}

最近10笔记录:
$recentLines

请仅基于以上真实数据回答用户的问题，不要编造数据。
如果是金额类问题，直接基于数据计算；如果数据不足以回答，明确告诉用户。
返回JSON: {"answer": "你的回答"}
""".trimIndent()
        }

        /** 格式化为 "M/d"，无备注。 */
        private fun formatDate(epochMs: Long): String {
            val d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), SHANGHAI)
            return "${d.monthValue}/${d.dayOfMonth}"
        }
    }
}
