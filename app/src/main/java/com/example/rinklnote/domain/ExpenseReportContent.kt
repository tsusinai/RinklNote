package com.example.rinklnote.domain

import com.example.rinklnote.data.network.dto.ExpenseReportResponse
import com.example.rinklnote.report.ReportHtml
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** HTML 作为版本化内容存入已有聊天表；历史文字消息无需迁移。 */
object ExpenseReportContent {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(report: ExpenseReportResponse): String = json.encodeToString(validate(report))
    fun decode(content: String): ExpenseReportResponse? = runCatching {
        require(content.length <= 160_000 && content.startsWith("{"))
        validate(json.decodeFromString<ExpenseReportResponse>(content))
    }.getOrNull()

    private fun validate(report: ExpenseReportResponse): ExpenseReportResponse {
        require(report.format == "expense-report-v1" && report.type in setOf("analysis", "summary", "anomaly"))
        require(report.source in setOf("llm", "fallback"))
        val html = requireNotNull(ReportHtml.clean(report.html)) { "报告内容暂时无法显示，请重试" }
        return report.copy(html = html, title = report.title.take(60), plainText = ReportHtml.plainText(html))
    }

    fun kind(type: String): MessageKind = when (type) {
        "summary" -> MessageKind.SUMMARY
        "anomaly" -> MessageKind.ANOMALY
        else -> MessageKind.ANALYSIS
    }

    fun intent(text: String): String? {
        if (!Regex("支出|消费|花销|花费|账单|花钱|开销").containsMatchIn(text)) return null
        return when {
            text.contains("异常") && Regex("分析|报告|情况|吗|是否|有.*异常").containsMatchIn(text) -> "anomaly"
            Regex("总结|复盘").containsMatchIn(text) -> "summary"
            Regex("分析|报告").containsMatchIn(text) -> "analysis"
            else -> null
        }
    }
}
