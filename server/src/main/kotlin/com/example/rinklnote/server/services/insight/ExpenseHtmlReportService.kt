package com.example.rinklnote.server.services.insight

import com.example.rinklnote.report.ReportHtml
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.tables.BillsTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ExpenseReportRequest(
    val type: String,
    val month: String? = null,
    val query: String? = null,
    val onlyIfAnomalous: Boolean = false
)

@Serializable
data class ExpenseReportResponse(
    val format: String,
    val type: String,
    val title: String,
    val month: String,
    val html: String,
    val plainText: String,
    val source: String,
    val totalExpenseMinor: Long,
    val expenseCount: Long,
    val hasAnomaly: Boolean
)

/** 只查询当前用户、指定月份的分类/日期聚合，绝不把账单备注或单笔明细送给模型。 */
class ExpenseHtmlReportService(private val generate: suspend (String, String) -> String?) {
    private data class Group(val date: String, val category: String, val amount: Long, val count: Long)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val titles = mapOf("analysis" to "支出分析", "summary" to "支出总结", "anomaly" to "支出异常")

    suspend fun create(userId: Long, request: ExpenseReportRequest): ExpenseReportResponse {
        val title = requireNotNull(titles[request.type]) { "报告类型必须为 analysis、summary 或 anomaly" }
        require((request.query?.length ?: 0) <= 300) { "报告请求过长" }
        val today = LocalDate.now(zone)
        val month = request.month ?: (InsightService.resolveYearMonth(request.query.orEmpty(), today)
            ?: today.withDayOfMonth(1)).let { YearMonth.from(it).toString() }
        require(month.matches(Regex("\\d{4}-(0[1-9]|1[0-2])"))) { "月份格式错误，需要 YYYY-MM" }
        val ym = YearMonth.parse(month)
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val previous = ym.minusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val (groups, previousTotal) = transaction {
            val amount = BillsTable.amountMinor.sum()
            val count = BillsTable.id.count()
            val rows = BillsTable.select(BillsTable.date, BillsTable.categoryName, amount, count)
                .where { (BillsTable.userId eq userId) and (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "EXPENSE") and (BillsTable.date greaterEq start) and (BillsTable.date less end) }
                .groupBy(BillsTable.date, BillsTable.categoryName).map {
                    Group(Instant.ofEpochMilli(it[BillsTable.date]).atZone(zone).toLocalDate().toString(),
                        it[BillsTable.categoryName], it[amount] ?: 0L, it[count])
                }
            val prev = BillsTable.select(amount).where {
                (BillsTable.userId eq userId) and (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "EXPENSE") and (BillsTable.date greaterEq previous) and (BillsTable.date less start)
            }.first()[amount] ?: 0L
            rows to prev
        }
        val total = groups.sumOf { it.amount }
        val count = groups.sumOf { it.count }
        val categories = groups.groupBy { it.category }.mapValues { it.value.sumOf { row -> row.amount } }
            .toList().sortedByDescending { it.second }
        val days = groups.groupBy { it.date }.mapValues { it.value.sumOf { row -> row.amount } }.toSortedMap()
        val average = if (days.isEmpty()) 0L else total / days.size
        val spikes = days.filterValues { average > 0 && BigDecimal(it) > BigDecimal(average).multiply(BigDecimal("1.5")) }
        val hasAnomaly = if (request.onlyIfAnomalous) spikes.containsKey(today.toString()) else spikes.isNotEmpty()
        val facts = buildJsonObject {
            put("月份", month); put("总支出", "¥${Money.format(total)}"); put("支出笔数", count)
            put("有支出的天数", days.size); put("有支出日的日均", "¥${Money.format(average)}")
            put("上月总支出", "¥${Money.format(previousTotal)}")
            put("分类汇总", JsonArray(categories.map { (name, value) -> buildJsonObject {
                put("分类", name); put("支出", "¥${Money.format(value)}"); put("占比百分数", percentage(value, total))
            } }))
            put("每日汇总", JsonArray(days.map { (date, value) -> buildJsonObject {
                put("日期", date); put("支出", "¥${Money.format(value)}")
            } }))
            put("异常日期", JsonArray(spikes.map { (date, value) -> buildJsonObject {
                put("日期", date); put("支出", "¥${Money.format(value)}")
            } }))
        }
        val instruction = when (request.type) {
            "analysis" -> "分析分类占比、每日支出趋势和相对上月的变化，给出可执行的观察建议。"
            "summary" -> "简洁总结本月支出，列出二至三个重点和下月可尝试的行动。"
            else -> "解释异常日期（仅为超过有支出日日均的1.5倍），说明这不是错误消费的判定；若没有则说明未发现明显波动。"
        }
        // 用户自由输入只用于本地解析月份，不加入模型提示词。
        val prompt = """
            请生成「$month $title」的完整 HTML 账单分析页面。$instruction
            只能基于以下聚合事实，不得编造预算、商家、原因、单笔消费或未提供的历史信息。
            无支出时说明数据不足；上月总额为0时不要计算增长率。
            聚合事实：$facts
            仅返回 JSON：{"html":"<!doctype html><html><head><style>...</style></head><body><main>...</main></body></html>"}。
            你负责生成页面结构和样式，不是把文字套进一段 p 标签。要像一个可直接预览的 HTML 作品：
            1. 有主题标题、简洁摘要，以及总支出、笔数、日均等统计卡片。
            2. 展示主要分类的占比条或静态 SVG 环图，并配上真实金额与百分比。
            3. 展示日期支出趋势的静态 SVG 折线图或条形图；少于2个支出日时改用数据不足提示。
            4. 分析/总结侧重支出结构与行动建议；异常报告突出具体波动日期、金额和观察依据。
            5. 可包含分类汇总表、上月对比、两至三个洞察与建议，所有数值必须与事实一致。
            页面中文，350px手机屏幕可读，单列为主、小卡片用 flex-wrap 或 grid 自适应；正文约300至500字。
            统一16px间距，圆角12至18px，清爽简洁、无阴影；不要固定页面高度、定位浮层或留下空白占位。
            样式可以放在 head 的 style 或内联 style，仅使用普通平面 CSS 规则，不使用 @规则、媒体块、动画、URL、外部字体。
            使用 App 提供的 CSS 变量：--text、--muted、--surface、--panel、--primary（品牌蓝）、--expense、--income、--divider。
            body 的样式只作用于报告容器；默认已有 metric/grid/amount/muted/highlight/warning/label/note 类，也可以自定义类名。
            可用 HTML：main/article/header/footer/section/div/span/figure/figcaption/h1/h2/h3/p/ul/ol/li/dl/dt/dd/strong/em/small/br/hr/table/caption/thead/tbody/tr/th/td。
            SVG 仅用 svg/g/rect/circle/ellipse/line/polyline/polygon/path/text/tspan/title/desc，可用 viewBox 与数值几何属性。
            必须是闭合标签的 XML 兼容 HTML；meta/br/hr 必须自闭合，文本中的 & 写成 &amp;，不要注释、CDATA或外部实体。
            不使用脚本、链接、图片、iframe、表单、事件属性、原生桥或 Markdown 围栏。总 HTML 不超过12000字符。
        """.trimIndent()
        val generated = if (total <= 0 || (request.onlyIfAnomalous && !hasAnomaly)) null else try {
            generate("你是个人支出分析助手和 HTML 报告编辑。只根据聚合数据生成报告，必须返回 JSON 对象。", prompt)
                ?.let { Json { ignoreUnknownKeys = true }.decodeFromString<ModelReport>(it).html }
                ?.let(ReportHtml::clean)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val fallback = buildString {
            append("<section><h2>${ReportHtml.escape(title)}</h2>")
            if (total <= 0) append("<p>本月暂无支出记录，记录更多账单后再来看看。</p>") else {
                append("<p>本月支出 ¥${Money.format(total)}，共 $count 笔，有支出日的日均为 ¥${Money.format(average)}。</p>")
                if (request.type == "anomaly") {
                    append("<p>${if (spikes.isEmpty()) "未发现超过日均1.5倍的支出日。" else "${spikes.size} 天支出超过日均1.5倍，可结合当时的计划核对。"}</p>")
                    append("<ul>")
                    spikes.forEach { (date, value) -> append("<li>$date · ¥${Money.format(value)}</li>") }
                    append("</ul>")
                } else {
                    append("<h3>主要支出</h3><table><tbody>")
                    categories.take(8).forEach { (name, value) ->
                        append("<tr><td>${ReportHtml.escape(name)}</td><td>¥${Money.format(value)}</td><td>${percentage(value, total)}%</td></tr>")
                    }
                    append("</tbody></table>")
                }
                append("<p class=\"muted\">AI 暂未生成解读，以上为账单聚合的基础报告。</p>")
            }
            append("</section>")
        }
        val html = generated ?: fallback
        return ExpenseReportResponse("expense-report-v1", request.type, title, month, html,
            ReportHtml.plainText(html), if (generated != null) "llm" else "fallback", total, count, hasAnomaly)
    }

    private fun percentage(amount: Long, total: Long): Int = if (total <= 0) 0 else
        BigDecimal(amount).multiply(BigDecimal(100)).divide(BigDecimal(total), 0, RoundingMode.HALF_UP).toInt()

    @Serializable private data class ModelReport(val html: String)
}
