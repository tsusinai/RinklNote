package com.example.rinklnote.ui.screen.quickadd

import com.example.rinklnote.util.Money
import java.time.LocalDate
import java.time.MonthDay
import java.time.Year

/**
 * 小票 OCR 识别文本 → 记账候选提取（纯 JVM，可单测）。
 *
 * 输入是 ML Kit 中文文本识别的全文本（按行分割），输出三类候选：
 * - **金额**：按可信度分档——「合计/总计/实付/应付/金额」标签行 > 货币符号前缀 >
 *   「元/圆/块」后缀；同档按出现顺序，跨档按档位；**不做裸数字兜底**（小票里单价、
 *   会员号、条码全是裸数字，误当金额比漏提更糟）。同值去重。
 * - **日期**：`2026-09-18` / `2026/9/18` / `2026年9月18日` / `2026.9.18` 全格式 +
 *   `9月18日` 缺年格式（补当前年份）；非法日期（13月45日）跳过；去重保序。
 * - **商家**：`商户/收款方/商家` 标签行优先，其次取票头前几行中「像店名」的行
 *   （含中文/字母、非纯数字、不含「小票/订单/收银/电话/地址」等元信息词），最多 3 个。
 *
 * 全部为纯字符串几何/词法处理，不依赖 ML Kit 类型，便于单测与未来换引擎复用。
 */
object ReceiptOcrParser {

    /** 提取结果：三类候选均已去重、按可信度排序。 */
    data class ReceiptCandidates(
        val amounts: List<Double> = emptyList(),
        val dates: List<LocalDate> = emptyList(),
        val merchants: List<String> = emptyList()
    )

    // ── 金额：三档正则（档位即可信度，小者更可信） ──

    /** 档 0：合计/总计/实付/应付/实收/金额 标签行。 */
    private val totalLabelRegex =
        Regex("""(?:合计|总计|实付|应付|实收|金额)[:：]?\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")

    /** 档 1：货币符号前缀。 */
    private val symbolRegex = Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""")

    /** 档 2：单位后缀。 */
    private val unitRegex = Regex("""(\d+(?:\.\d{1,2})?)\s*[元圆块]""")

    // ── 日期 ──

    /** 全格式：年-月-日（分隔符 - / . 年月日 均认）。 */
    private val fullDateRegex =
        Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")

    /** 缺年格式：X月X日（年份补当前年）。 */
    private val monthDayRegex = Regex("""(\d{1,2})月(\d{1,2})日""")

    // ── 商家 ──

    /** 标签行形态的商家。 */
    private val merchantLabelRegex = Regex("""(?:商户|收款方|商家)[:：]?\s*(\S{1,20})""")

    /** 票头元信息词：命中其一的行不像店名。 */
    private val metaWords = listOf("小票", "订单", "收银", "电话", "地址", "欢迎", "凭证", "编号", "机号", "流水")

    fun extract(text: String): ReceiptCandidates {
        if (text.isBlank()) return ReceiptCandidates()
        return ReceiptCandidates(
            amounts = extractAmounts(text),
            dates = extractDates(text),
            merchants = extractMerchants(text)
        )
    }

    // ── 金额 ──

    private fun extractAmounts(text: String): List<Double> {
        val result = ArrayList<Double>()
        // 档位从可信到低信：合计标签 → 货币符号 → 单位后缀
        totalLabelRegex.findAll(text).forEach { addIfNew(result, it.groupValues[1].toDoubleOrNull()) }
        symbolRegex.findAll(text).forEach { addIfNew(result, it.groupValues[1].toDoubleOrNull()) }
        unitRegex.findAll(text).forEach { addIfNew(result, it.groupValues[1].toDoubleOrNull()) }
        return result
    }

    private fun addIfNew(target: MutableList<Double>, value: Double?) {
        // 超界数字（订单号/条码被误拼进金额位等解析噪声）直接丢弃：
        // 弹窗点选会把「元」转分（Money.yuanToMinor），溢出/Infinity 会崩。
        val v = value?.takeIf { Money.isPlausibleParsedYuan(it) } ?: return
        // 以两位小数量化做去重键，避免 0.1+0.2 类浮点尾差产生「同值不同键」
        val key = Math.round(v * 100.0)
        if (target.none { Math.round(it * 100.0) == key }) target.add(v)
    }

    // ── 日期 ──

    private fun extractDates(text: String): List<LocalDate> {
        val result = ArrayList<LocalDate>()
        fullDateRegex.findAll(text).forEach { m ->
            val date = runCatching {
                LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt()
                )
            }.getOrNull()
            if (date != null) addIfNew(result, date)
        }
        // 缺年格式：只在没有全格式日期时补（避免同一天两种写法重复/年份歧义）
        if (result.isEmpty()) {
            monthDayRegex.findAll(text).forEach { m ->
                val date = runCatching {
                    Year.now().atMonthDay(
                        MonthDay.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                    )
                }.getOrNull()
                if (date != null) addIfNew(result, date)
            }
        }
        return result
    }

    private fun addIfNew(target: MutableList<LocalDate>, date: LocalDate) {
        if (target.none { it == date }) target.add(date)
    }

    // ── 商家 ──

    private fun extractMerchants(text: String): List<String> {
        val result = ArrayList<String>()
        merchantLabelRegex.findAll(text).forEach { addIfNew(result, it.groupValues[1].trim()) }
        // 标签行没命中时，从票头找「像店名」的行（前 4 行里挑，最多补到 3 个候选）
        if (result.isEmpty()) {
            text.lines().take(4)
                .filter { looksLikeStoreName(it.trim()) }
                .forEach { addIfNew(result, it.trim()) }
        }
        return result.take(3)
    }

    /** 像店名：2~20 字、含中文或字母、非纯数字/符号、不含票头元信息词、不长得像金额。 */
    private fun looksLikeStoreName(line: String): Boolean {
        if (line.length !in 2..20) return false
        if (!line.any { it.code in 0x4E00..0x9FFF || it.isLetter() }) return false
        if (metaWords.any { line.contains(it) }) return false
        // 纯数字/金额行（如「20260918」「¥12.00」）不算店名
        if (line.all { it.isDigit() || it in ".,-/:¥￥ " }) return false
        return true
    }

    private fun addIfNew(target: MutableList<String>, value: String) {
        if (value.isNotEmpty() && target.none { it == value }) target.add(value)
    }
}
