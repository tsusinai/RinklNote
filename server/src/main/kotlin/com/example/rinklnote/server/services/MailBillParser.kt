package com.example.rinklnote.server.services

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 账单邮件正文解析（2026-09-18 Task 4.4）：支付宝 / 微信账单邮件 → 结构化账单。
 *
 * **隐私红线**：正文只在内存解析，不落盘、不写日志、绝不送 LLM —— 本类是纯函数，
 * 输入字符串随调用栈生灭；日志侧只允许出现金额与解析结果（merchant 取自规则命中的
 * 字段而非全文）。
 *
 * 解析口径（ tolerant，两种真实版式都能吃）：
 * 1. 先剥 HTML（style/script 整块移除，标签换行，常见实体解码）；
 * 2. 金额 = 全文首个 `[¥￥]` 后的数字（两位小数内）；**注意邮件里可能有多个金额**，
 *    取首个是主流版式（账单邮件把支付金额放在标题区）；
 * 3. 商家 = 「商户/收款方/商品说明/商品」键值（同行冒号 或 表格键行取下一非空行）；
 * 4. 支付日期 = `2026-09-17 / 2026/09/17 / 2026年9月17日` 三种写法（解析不出回退 null → 入账用当天）。
 */
object MailBillParser {

    /** 解析结果（金额整数分）。 */
    data class ParsedBill(val amountMinor: Long, val merchant: String, val paidAtMillis: Long?)

    // 金额数字部分：允许千分位「1,234.56」（逗号分组必须 3 位），也兼容普通「1234.56」；
    // 不带千分位分组时放宽到 10 位。
    private val AMOUNT_NUMBER = Regex("""(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,10})(?:\.[0-9]{1,2})?""")
    private val AMOUNT = Regex("""[¥￥]\s*(${AMOUNT_NUMBER.pattern})""")
    private val KEY_INLINE = Regex("""(?:商户|收款方|商品说明|商品)\s*[：:]\s*([^\n¥￥]{2,40})""")
    private val KEY_LINES = setOf("商户", "收款方", "商品说明", "商品")
    private val DATE = Regex("""((?:19|20)\d{2})[-/年.](\d{1,2})[-/月.](\d{1,2})""")

    /** 剥 HTML → 纯文本（标签换行 + 实体解码 + 空白折叠）。纯函数，可单测。 */
    fun stripHtml(html: String): String {
        val noBlocks = html
            .replace(Regex("""(?is)<(style|script)[^>]*>.*?</\1\s*>"""), "")
            .replace(Regex("""(?is)<!--.*?-->"""), "")
        val withBreaks = noBlocks.replace(Regex("""(?i)<(br|/p|/div|/tr|/td|/th|/li|/h[1-6])[^>]*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        return withBreaks
            .replace("&yen;", "¥").replace("&yen", "¥").replace("&#165;", "¥").replace("&#xffe5;", "￥")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .split('\n').joinToString("\n") { it.trim() }
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }

    /**
     * 解析账单邮件（HTML 或纯文本均可）。金额 / 商家任一缺失返回 null（调用方计失败并告警）。
     */
    fun parse(raw: String): ParsedBill? {
        val text = if (raw.contains('<') && raw.contains('>')) stripHtml(raw) else raw
        val amount = AMOUNT.find(text)?.groupValues?.get(1)
            ?.replace(",", "") // 千分位「1,234.56」→「1234.56」，防逗号截断错账
            ?.toDoubleOrNull()
            ?.let { Money.toMinor(it) }
            ?: return null
        val merchant = extractMerchant(text) ?: return null
        return ParsedBill(amountMinor = amount, merchant = merchant, paidAtMillis = extractDate(text))
    }

    /** 商家提取：同行键值对优先，其次「键独占一行 → 下一非空行」。 */
    internal fun extractMerchant(text: String): String? {
        KEY_INLINE.find(text)?.let { return it.groupValues[1].trim().trimEnd('，', '。', ' ') }
        val lines = text.lines().map { it.trim() }
        for (i in lines.indices) {
            if (lines[i] in KEY_LINES) {
                val value = lines.drop(i + 1).firstOrNull { it.isNotEmpty() } ?: break
                return value.take(40).trimEnd('，', '。', ' ')
            }
        }
        return null
    }

    /** 支付日期提取（业务时区当日 0 点）；解析不出返回 null。 */
    internal fun extractDate(text: String): Long? {
        val m = DATE.find(text) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        return runCatching {
            LocalDate.of(y, mo, d).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** 日期格式化（测试/日志用）。 */
    internal fun formatDate(millis: Long): String =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
}
