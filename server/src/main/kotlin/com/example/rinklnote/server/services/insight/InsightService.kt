package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillDTO
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.tables.BotConfigTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.PushLogTable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
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

/**
 * 旧客户端兼容字段（元）统一说明：
 * Json 默认 `encodeDefaults = false`，带默认值的属性在「取值等于默认值」时会被整个省略。
 * 这些兼容字段的默认值都由 amountMinor 换算而来，恰好恒等于默认值，因此**必须**加
 * `@EncodeDefault(ALWAYS)` 才能保证始终被序列化输出；否则旧客户端会因字段缺失反序列化失败。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CategoryAmount(
    val name: String,
    val amountMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val amount: Double = Money.fromMinor(amountMinor)
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MonthlyAnomalyResponse(
    val month: String,
    val totalExpenseMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val totalExpense: Double = Money.fromMinor(totalExpenseMinor),
    val activeDays: Int,
    val avgDailyExpenseMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val avgDailyExpense: Double = Money.fromMinor(avgDailyExpenseMinor),
    val spikeDays: List<MonthlySpike>,
    val biggestSingle: SingleBill?,
    val topCategories: List<CategoryAmount>,
    val analysis: String
)

@Serializable
private data class MonthlyAnomalyAnalysis(val analysis: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MonthlySpike(
    val date: String,
    val amountMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val amount: Double = Money.fromMinor(amountMinor),
    val ratioPct: Int
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SingleBill(
    val amountMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val amount: Double = Money.fromMinor(amountMinor),
    val categoryName: String,
    val date: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DailyReportResponse(
    val date: String,
    val totalExpenseMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val totalExpense: Double = Money.fromMinor(totalExpenseMinor),
    val totalIncomeMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val totalIncome: Double = Money.fromMinor(totalIncomeMinor),
    val expenseCategories: List<CategoryAmount>,
    val incomeCategories: List<CategoryAmount>,
    val billCount: Int,
    val summary: String
)

// 注：此前漏了 @Serializable，而 InsightRoutes 的 /monthly-review 是直接 call.respond(该类型)，
// 运行时会抛「Serializer for class 'MonthlyReviewResponse' is not found」。补上。
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MonthlyReviewResponse(
    val month: String,
    val summary: String,
    val highlights: List<String>,
    val totalExpenseMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val totalExpense: Double = Money.fromMinor(totalExpenseMinor),
    val totalIncomeMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val totalIncome: Double = Money.fromMinor(totalIncomeMinor),
    val activeDays: Int,
    val avgDailyExpenseMinor: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val avgDailyExpense: Double = Money.fromMinor(avgDailyExpenseMinor),
    val spikeDays: List<MonthlySpike>,
    val biggestSingle: SingleBill?,
    val topCategories: List<CategoryAmount>
)

class InsightService(
    private val llmParser: LLMParser,
    private val billService: BillService,
    private val anomalyThreshold: Double = 1.5
) {
    suspend fun dailyReport(userId: Long, dayStart: Long, dayEnd: Long): DailyReportResponse {
        val stats = billService.monthlyStats(userId, dayStart, dayEnd)
        // 报表标题跟随统计窗口的起始日：窗口是今天就叫「今日」，是别的日子直接报日期
        // （调度器在早上推的是「昨天」的日报，标题不能写死「今日」）。
        val reportDate = java.time.Instant.ofEpochMilli(dayStart).atZone(SHANGHAI).toLocalDate()
        val isToday = reportDate == java.time.LocalDate.now(SHANGHAI)
        val date = reportDate.toString()
        val title = if (isToday) "✅ 今日账单总结" else "✅ $date 账单总结"
        val summary = buildString {
            appendLine(title)
            appendLine("支出: ￥${Money.format(stats.totalExpenseMinor)}")
            if (stats.totalIncomeMinor > 0) appendLine("收入: ￥${Money.format(stats.totalIncomeMinor)}")
            appendLine("共 ${stats.billCount} 笔")
            if (stats.topExpenseCategories.isNotEmpty()) {
                appendLine("支出分类:")
                stats.topExpenseCategories.take(3).forEach {
                    appendLine("  - ${it.first}: ￥${Money.format(it.second)}")
                }
            }
        }
        return DailyReportResponse(
            date = date,
            totalExpenseMinor = stats.totalExpenseMinor,
            totalIncomeMinor = stats.totalIncomeMinor,
            expenseCategories = stats.topExpenseCategories.map { CategoryAmount(it.first, it.second) },
            incomeCategories = stats.topIncomeCategories.map { CategoryAmount(it.first, it.second) },
            billCount = stats.billCount.toInt(),
            summary = summary.toString()
        )
    }

    /**
     * 日报推送文案（QQ 主动推送专用，人话化）。
     * 结构：标题 → LLM 一句话点评（失败回退省略）→ 支出/收入行 → 大头分类 →
     * 连续记账 🔥 → 克制报喜 👍 → 预算进度 📊（有预算才出现）。
     * 隐私 NFR：喂给 LLM 的只有聚合数字，无明细/备注。
     */
    suspend fun dailyPushCopy(userId: Long, dayStart: Long, dayEnd: Long): String? {
        val stats = billService.monthlyStats(userId, dayStart, dayEnd)
        if (stats.totalExpenseMinor <= 0 && stats.totalIncomeMinor <= 0) return null

        val bills = billService.allBills(userId)
        val reportDate = Instant.ofEpochMilli(dayStart).atZone(SHANGHAI).toLocalDate()

        // 前 7 天日均支出（不含统计日），用于对比与「克制」判定。
        val prev7Start = dayStart - 7L * DAY_MS
        val prev7 = bills.filter { it.billType == "EXPENSE" && it.date >= prev7Start && it.date < dayStart }
        val prev7AvgMinor = if (prev7.isNotEmpty()) prev7.sumOf { it.amountMinor } / 7.0 else 0.0

        // 连续记账天数：从统计日往回数有账的日子（日期按「当日 0 点」桶分组，中国无夏令时，整除安全）。
        val streak = run {
            val days = bills.map { it.date / DAY_MS }.toHashSet()
            var s = 0
            var cursor = dayStart / DAY_MS
            while (days.contains(cursor)) { s++; cursor-- }
            s
        }

        // 当月总预算（无分类的 MONTHLY 预算）与本月截至统计日的支出。
        val budgetMinor = transaction {
            BudgetsTable.selectAll().where {
                (BudgetsTable.userId eq userId) and
                    (BudgetsTable.categoryId.isNull()) and
                    (BudgetsTable.deleted eq false) and
                    (BudgetsTable.monthStart eq monthStartOf(reportDate))
            }.firstOrNull()?.get(BudgetsTable.amountMinor)
        } ?: 0L
        val monthExpenseMinor = if (budgetMinor > 0) {
            bills.filter { it.billType == "EXPENSE" && it.date >= monthStartOf(reportDate) && it.date < dayEnd }
                .sumOf { it.amountMinor }
        } else 0L

        // 一句话点评：只喂聚合；失败（网络/解析）静默省略该行，模板兜底。
        val verdict = try {
            val prompt = buildString {
                appendLine("昨日记账聚合（无任何明细/备注）:")
                appendLine("- 支出总额: ¥${Money.format(stats.totalExpenseMinor)}（${stats.billCount} 笔）")
                if (stats.totalIncomeMinor > 0) appendLine("- 收入总额: ¥${Money.format(stats.totalIncomeMinor)}")
                if (prev7AvgMinor > 0) appendLine("- 前7天日均支出: ¥${Money.format(prev7AvgMinor.toLong())}")
                if (stats.topExpenseCategories.isNotEmpty()) {
                    append("- 支出大头: ")
                    append(stats.topExpenseCategories.take(2).joinToString("、") { "${it.first} ¥${Money.format(it.second)}" })
                    appendLine()
                }
                appendLine()
                append("请用一句不超过 25 字的中文点评昨天，亲切自然、不说教、不堆感叹号，例如「昨天花得不算多，餐饮依旧是大头」。")
                append("返回JSON: {\"answer\": \"...\"}")
            }
            llmParser.chat("你是用户的记账助手，说话简短自然。必须返回 JSON 对象。", prompt)
                ?.let { Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<QueryResponse>(it) }
                ?.answer?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

        return buildDailyPushMessage(
            reportDate = reportDate,
            verdict = verdict,
            expenseMinor = stats.totalExpenseMinor,
            incomeMinor = stats.totalIncomeMinor,
            billCount = stats.billCount.toInt(),
            topCategories = stats.topExpenseCategories.take(3),
            prev7AvgMinor = prev7AvgMinor,
            streak = streak,
            budgetMinor = budgetMinor,
            monthExpenseMinor = monthExpenseMinor
        )
    }

    /** 日报消息拼装（纯函数，可单测）。 */
    fun buildDailyPushMessage(
        reportDate: LocalDate,
        verdict: String?,
        expenseMinor: Long,
        incomeMinor: Long,
        billCount: Int,
        topCategories: List<Pair<String, Long>>,
        prev7AvgMinor: Double,
        streak: Int,
        budgetMinor: Long,
        monthExpenseMinor: Long
    ): String = buildString {
        appendLine("✅ ${reportDate} 账单总结")
        verdict?.let { appendLine(it) }
        val expenseLine = "支出 ¥${Money.format(expenseMinor)} · $billCount 笔"
        appendLine(if (incomeMinor > 0) "$expenseLine（收入 ¥${Money.format(incomeMinor)}）" else expenseLine)
        if (topCategories.isNotEmpty()) {
            append("大头：")
            appendLine(topCategories.joinToString("、") { "${it.first} ¥${Money.format(it.second)}" })
        }
        if (streak >= 2) appendLine("🔥 已连续记账 $streak 天")
        if (prev7AvgMinor > 0 && expenseMinor < prev7AvgMinor * 0.5) {
            appendLine("👍 只花了平时的一半不到，克制的一天")
        }
        if (budgetMinor > 0) {
            val pct = (monthExpenseMinor * 100 / budgetMinor).toInt()
            appendLine("📊 本月 ¥${Money.format(monthExpenseMinor)} / 预算 ¥${Money.format(budgetMinor)}（$pct%）")
        }
    }.trimEnd()

    private fun monthStartOf(date: LocalDate): Long =
        date.withDayOfMonth(1).atStartOfDay(SHANGHAI).toInstant().toEpochMilli()

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
        val totalExpense = stats.totalExpenseMinor
        val totalIncome = stats.totalIncomeMinor
        val byCategory = stats.topExpenseCategories

        val context = """
账单数据 ($month):
- 总支出: ¥${Money.format(totalExpense)}
- 总收入: ¥${Money.format(totalIncome)}
- 支出分类TOP5: ${byCategory.joinToString { "${it.first} ¥${Money.format(it.second)}" }}

请你用自然亲切的中文写一段简洁的月度消费总结（80-150字），语气温和，别像冷冰冰的报告，并列出2-3个值得关注的点(highlights)。

返回JSON: {"summary": "总结文字", "highlights": ["亮点1", "亮点2"]}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个贴心又不失专业的个人财务助手，用自然亲切的中文回答，别写成冷冰冰的报告，语气温和。你必须返回一个 JSON 对象。",
                context
            )
            if (jsonStr != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<MonthlySummaryResponse>(jsonStr)
                return parsed
            }
        } catch (_: Exception) {}

        return MonthlySummaryResponse(
            summary = "${month} 总支出 ¥${Money.format(totalExpense)}，收入 ¥${Money.format(totalIncome)}，钱要花得开心，也要记得给自己留一点～",
            highlights = emptyList()
        )
    }

    /**
     * 按月异常分析（规则版，无 LLM）。与 anomalyCheck（仅当天）互补：
     * 以「本月实际有支出的天数为分母」算日均，找出本月支出超过该日均 [anomalyThreshold] 倍的
     * 超标日、当月最大单笔、以及支出分类集中度。可复用（App/网页/QQ 推送）。
     */
    suspend fun monthlyAnomaly(userId: Long, month: String): MonthlyAnomalyResponse {
        val f = computeMonthlyFacts(userId, month)
        val analysis = anomalyAnalysis(f)
        return MonthlyAnomalyResponse(
            month = f.month, totalExpenseMinor = f.totalExpense, activeDays = f.activeDays,
            avgDailyExpenseMinor = f.avgDailyExpense, spikeDays = f.spikeDays,
            biggestSingle = f.biggestSingle, topCategories = f.topCategories,
            analysis = analysis
        )
    }

    /** LLM 生成月度异常分析；失败/缺失回退到规则文案（含同样的事实点）。 */
    private suspend fun anomalyAnalysis(f: MonthlyFacts): String {
        val spikeLines = if (f.spikeDays.isEmpty()) "无"
            else f.spikeDays.joinToString("、") { "${it.date} ¥${Money.format(it.amountMinor)}（超日均${it.ratioPct}%）" }
        val topLines = f.topCategories.joinToString { "${it.name} ¥${Money.format(it.amountMinor)}" }
        val biggest = f.biggestSingle?.let { "${it.categoryName} ¥${Money.format(it.amountMinor)}（${it.date}）" } ?: "无"
        val context = """
账单异常数据 (${f.month}):
- 总支出: ¥${Money.format(f.totalExpense)}
- 记账天数: ${f.activeDays} 天，日均支出: ¥${Money.format(f.avgDailyExpense)}
- 超标日: ${spikeLines}
- 最大单笔: ${biggest}
- 消费集中TOP5: ${topLines}

请用自然亲切的中文写一段 60-120 字的月度异常分析，指出这个月哪里花钱异常、哪些点值得注意，语气温和、别责怪，也别过度渲染。若该月整体平稳，就自然说明无明显异常。
返回JSON: {"analysis": "你的分析"}
""".trimIndent()
        return try {
            val jsonStr = llmParser.chat(
                "你是一个贴心又不唠叨的记账异常分析助手，用自然亲切的中文。必须返回 JSON 对象。",
                context
            )
            val ans = jsonStr?.let {
                Json { ignoreUnknownKeys = true; isLenient = true }
                    .decodeFromString<MonthlyAnomalyAnalysis>(it).analysis.trim()
            }
            if (!ans.isNullOrBlank()) ans else fallbackAnomalyAnalysis(f)
        } catch (_: Exception) {
            fallbackAnomalyAnalysis(f)
        }
    }

    private fun fallbackAnomalyAnalysis(f: MonthlyFacts): String {
        val sb = StringBuilder("本月支出 ¥${Money.format(f.totalExpense)}，记账 ${f.activeDays} 天，日均 ¥${Money.format(f.avgDailyExpense)}。")
        if (f.spikeDays.isEmpty()) {
            sb.append("整体节奏比较平稳，没有明显超标日。")
        } else {
            sb.append("有 ${f.spikeDays.size} 天支出明显超标：")
                .append(f.spikeDays.joinToString("、") { "${it.date} ¥${Money.format(it.amountMinor)}（超日均${it.ratioPct}%）" })
                .append("。")
        }
        f.biggestSingle?.let { sb.append("最大单笔是${it.categoryName} ¥${Money.format(it.amountMinor)}。") }
        return sb.toString()
    }

    /**
     * 月度复盘：月度总结（LLM）+ 异常事实，一次返回。总结的上下文里追加了异常事实
     * （超标日/最大单笔/分类集中度），LLM 复盘时能自然点出值得注意的点。所有下游
     * （App/网页/QQ 推送）都消费这个统一入口，不再各问各的。
     */
    suspend fun monthlyReview(userId: Long, month: String): MonthlyReviewResponse {
        val f = computeMonthlyFacts(userId, month)

        val spikeLines = if (f.spikeDays.isEmpty()) "无"
            else f.spikeDays.joinToString("、") { "${it.date} ¥${Money.format(it.amountMinor)}（超日均${it.ratioPct}%）" }
        val topLines = f.topCategories.joinToString { "${it.name} ¥${Money.format(it.amountMinor)}" }

        val context = """
账单数据 ($month):
- 总支出: ¥${Money.format(f.totalExpense)}
- 总收入: ¥${Money.format(f.totalIncome)}
- 记账天数: ${f.activeDays} 天，日均支出: ¥${Money.format(f.avgDailyExpense)}
- 支出分类TOP5: ${topLines}
- 超标日: ${spikeLines}
- 最大单笔: ${f.biggestSingle?.let { "${it.categoryName} ¥${Money.format(it.amountMinor)}（${it.date}）" } ?: "无"}

请你用自然亲切的中文写一段简洁的月度消费复盘（80-150字），语气温和，别像冷冰冰的报告，并列出2-3个值得关注的点(highlights)。若某月超标日/最大单笔异常明显，请在总结中自然点出，但别过度渲染。

返回JSON: {"summary": "总结文字", "highlights": ["亮点1", "亮点2"]}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个贴心又不失专业的个人财务助手，用自然亲切的中文回答，别写成冷冰冰的报告，语气温和。你必须返回一个 JSON 对象。",
                context
            )
            if (jsonStr != null) {
                val parsed = Json { ignoreUnknownKeys = true; isLenient = true }
                    .decodeFromString<MonthlySummaryResponse>(jsonStr)
        return MonthlyReviewResponse(
            month = month, summary = parsed.summary, highlights = parsed.highlights,
            totalExpenseMinor = f.totalExpense, totalIncomeMinor = f.totalIncome,
            activeDays = f.activeDays, avgDailyExpenseMinor = f.avgDailyExpense,
            spikeDays = f.spikeDays, biggestSingle = f.biggestSingle, topCategories = f.topCategories
        )
            }
        } catch (_: Exception) {}

        val sumMsg = buildString {
            append("${month} 总支出 ¥${Money.format(f.totalExpense)}，收入 ¥${Money.format(f.totalIncome)}")
            if (f.activeDays > 0) append("，日均 ¥${Money.format(f.avgDailyExpense)}")
        }
        return MonthlyReviewResponse(
            month = month, summary = sumMsg + "，钱要花得开心，也要记得给自己留一点～", highlights = emptyList(),
            totalExpenseMinor = f.totalExpense, totalIncomeMinor = f.totalIncome,
            activeDays = f.activeDays, avgDailyExpenseMinor = f.avgDailyExpense,
            spikeDays = f.spikeDays, biggestSingle = f.biggestSingle, topCategories = f.topCategories
        )
    }

    /** 按月聚合异常事实（规则版，无 LLM），供 monthlyAnomaly / monthlyReview 共享。 */
    private suspend fun computeMonthlyFacts(userId: Long, month: String): MonthlyFacts {
        require(month.matches(Regex("""^\d{4}-(0[1-9]|1[0-2])$"""))) { "月份格式错误，需要 YYYY-MM" }
        val (y, m) = month.split("-").map { it.toInt() }
        val monthStart = LocalDate.of(y, m, 1).atStartOfDay(SHANGHAI).toInstant().toEpochMilli()
        val nextMonthStart = LocalDate.of(y, m, 1).plusMonths(1).atStartOfDay(SHANGHAI).toInstant().toEpochMilli()

        val inWindow = billService.allBills(userId).filter { it.date >= monthStart && it.date < nextMonthStart }
        val expenses = inWindow.filter { it.billType == "EXPENSE" }
        val totalExpense = expenses.sumOf { it.amountMinor }
        val totalIncome = inWindow.filter { it.billType == "INCOME" }.sumOf { it.amountMinor }

        val byDay = expenses.groupBy { dayKey(it.date) }
        val activeDays = byDay.size
        // 日均支出（整数分）用于波动比例计算；与整数分同单位，避免整数相除漂移。
        val avg = if (activeDays > 0) Money.toMinor(totalExpense.toDouble() / activeDays) else 0L

        val spikes = byDay.mapNotNull { (d, bills) ->
            val dayTotal = bills.sumOf { it.amountMinor }
            if (avg > 0 && dayTotal > avg * anomalyThreshold) {
                MonthlySpike(date = d, amountMinor = dayTotal, ratioPct = ((dayTotal.toDouble() / avg - 1) * 100).toInt())
            } else null
        }.sortedByDescending { it.ratioPct }

        val biggest = expenses.maxByOrNull { it.amountMinor }?.let {
            SingleBill(amountMinor = it.amountMinor, categoryName = it.categoryName, date = dayKey(it.date))
        }

        val top = expenses.groupBy { it.categoryName }
            .mapValues { it.value.sumOf { b -> b.amountMinor } }
            .entries.sortedByDescending { it.value }.take(5)
            .map { CategoryAmount(it.key, it.value) }

        return MonthlyFacts(month, totalExpense, totalIncome, activeDays, avg, spikes, biggest, top)
    }

    private data class SummaryResponse(val summary: String)

data class MonthlyFacts(
        val month: String,
        val totalExpense: Long,
        val totalIncome: Long,
        val activeDays: Int,
        val avgDailyExpense: Long,
        val spikeDays: List<MonthlySpike>,
        val biggestSingle: SingleBill?,
        val topCategories: List<CategoryAmount>
    )

    private fun dayKey(epochMs: Long): String {
        val d = LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), SHANGHAI)
        return "${d.monthValue}/${d.dayOfMonth}"
    }

    suspend fun anomalyCheck(userId: Long): AnomalyResponse {
        val bills = billService.allBills(userId)
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000

        val recentExpenses = bills.filter { it.billType == "EXPENSE" && it.date >= thirtyDaysAgo }
        val dailyAvg = recentExpenses.sumOf { it.amountMinor } / 30.0

        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val todayExpense = bills.filter { it.billType == "EXPENSE" && it.date >= todayStart }
            .sumOf { it.amountMinor }

        val alerts = mutableListOf<AnomalyAlert>()

        if (dailyAvg > 0 && todayExpense > dailyAvg * 2.5) {
            // 重度超标：语气直接一些。
            val multiple = (todayExpense / dailyAvg * 10).toInt() / 10.0
            alerts.add(AnomalyAlert(
                level = "WARN",
                message = "今天已花 ¥${Money.format(todayExpense)}，是平时日均（¥${Money.format(dailyAvg.toLong())}）的 $multiple 倍，接下来收着点花",
                type = "DAILY_SPIKE"
            ))
        } else if (dailyAvg > 0 && todayExpense > dailyAvg * anomalyThreshold) {
            // 轻度超标：温和提醒，不说教。
            val pct = ((todayExpense.toDouble() / dailyAvg - 1) * 100).toInt()
            alerts.add(AnomalyAlert(
                level = "INFO",
                message = "今天已花 ¥${Money.format(todayExpense)}，比日均 ¥${Money.format(dailyAvg.toLong())} 高 $pct%，正常波动范围内不用太在意",
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

        val totalExpense = monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amountMinor }
        val totalIncome = monthBills.filter { it.billType == "INCOME" }.sumOf { it.amountMinor }
        val topCategories = monthBills.filter { it.billType == "EXPENSE" }
            .groupBy { it.categoryName }
            .mapValues { it.value.sumOf { b -> b.amountMinor } }
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
                "你是一个贴心又专业的财务查询助手，用自然亲切的中文回答，别写成冷冰冰的报告。你必须返回一个 JSON 对象: {\"answer\": \"你的回答\"}。只基于提供的数据回答，不要编造。",
                context
            )
            if (jsonStr != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<QueryResponse>(jsonStr)
                return parsed
            }
        } catch (_: Exception) {}

        return QueryResponse(answer = "抱歉，这个问题我一下子没太懂～ 你可以换个说法，比如「上个月交通花了多少」")
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

        val grouped = recent.groupBy { Pair(it.categoryName, it.amountMinor) }
        val best = grouped.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < config.minOccurrences) return null

        return mapOf(
            "label" to window.label,
            "categoryName" to best.key.first,
            "amount" to Money.format(best.key.second)
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

    data class HabitReminder(val label: String, val categoryName: String, val amountMinor: Long, val amount: Double = Money.fromMinor(amountMinor))

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

        val grouped = recent.groupBy { Pair(it.categoryName, it.amountMinor) }
        val best = grouped.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < config.minOccurrences) return null
        // 今日已记该分类则不提醒
        if (bills.any { it.billType == "EXPENSE" && it.date >= todayStart && it.categoryName == best.key.first }) return null

        // 防唠叨降频：近 7 天已推 ≥3 次习惯提醒、且最近 3 天（不含今天）用户仍未记该分类
        // → 提了也没响应，进入冷却，今天不推（省 QQ 主动消息配额）。
        val nowMs = now.toInstant().toEpochMilli()
        val habitPushCount = transaction {
            PushLogTable.selectAll().where {
                (PushLogTable.userId eq userId) and
                    (PushLogTable.type eq "HABIT") and
                    (PushLogTable.pushedAt greaterEq nowMs - 7L * DAY_MS)
            }.count()
        }
        if (habitPushCount >= 3) {
            val cat3Start = nowMs - 3L * DAY_MS
            val recordedRecently = bills.any {
                it.billType == "EXPENSE" && it.categoryName == best.key.first &&
                    it.date >= cat3Start && it.date < todayStart
            }
            if (!recordedRecently) return null
        }

        return HabitReminder(window.label, best.key.first, best.key.second)
    }

    /** LLM 润色习惯提醒文案，只喂聚合（无明细/备注），失败回退模板。 */
    suspend fun polishHabitCopy(habit: HabitReminder): String {
        val context = """
习惯提醒线索（聚合，无任何明细/备注）:
- 时段: ${habit.label}
- 常记分类: ${habit.categoryName}
- 常记金额: ¥${Money.format(habit.amountMinor)}
请用一句话（≤30字）给出自然亲切、不硬性的提醒文案，例如「${habit.label}时段你常点 ${habit.categoryName} ¥${Money.format(habit.amountMinor)}，记得记一笔吗？」
返回JSON: {"answer": "..."}
""".trimIndent()
        return try {
            val jsonStr = llmParser.chat("你是一个贴心又不唠叨的记账提醒助手，用自然亲切、不硬性的中文。必须返回 JSON 对象。", context)
            val parsed = jsonStr?.let { Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<QueryResponse>(it) }
            val ans = parsed?.answer?.trim()
            if (!ans.isNullOrBlank()) ans
            else "「${habit.label}」你常记 ${habit.categoryName} ¥${Money.format(habit.amountMinor)}，今天记了吗？"
        } catch (_: Exception) {
            "「${habit.label}」到点啦，你平时常记 ${habit.categoryName} ¥${Money.format(habit.amountMinor)}，今天记得补一笔呀～"
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
        private const val DAY_MS = 86_400_000L

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
            totalExpense: Long,
            totalIncome: Long,
            topCategories: List<Pair<String, Long>>,
            recentBills: List<BillDTO>
        ): String {
            val recentLines = recentBills.sortedByDescending { it.date }.take(10)
                .joinToString("\n") { "- ${formatDate(it.date)} ${it.categoryName} ¥${Money.format(it.amountMinor)}" }
            return """
用户问题: "$query"

可用分类: ${categories.joinToString("、")}

数据 ($year-$month):
- 月份: ${year}-${month}
- 总支出: ¥${Money.format(totalExpense)}
- 总收入: ¥${Money.format(totalIncome)}
- 支出分类TOP5: ${topCategories.joinToString { pair -> "${pair.first} ¥${Money.format(pair.second)}" }}

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
