package com.example.rinklnote.util

/**
 * Simple amount and keyword extraction from spoken Chinese text.
 * Examples:
 *   "午餐二十元" -> amount=20, category="三餐"
 *   "打车花了十五块" -> amount=15, category="交通"
 */
data class VoiceResult(
    val amount: Double?,
    val categoryName: String?,
    val remark: String
)

object VoiceParser {
    private val categories = mapOf(
        "餐" to "三餐", "饭" to "三餐", "吃" to "三餐", "早餐" to "三餐",
        "午餐" to "三餐", "晚餐" to "三餐", "零食" to "三餐",
        "日用" to "日用", "超市" to "日用", "百货" to "日用",
        "交通" to "交通", "打车" to "交通", "地铁" to "交通", "公交" to "交通", "加油" to "交通",
        "学习" to "学习", "书" to "学习", "教育" to "学习", "培训" to "学习",
        "运动" to "运动", "健身" to "运动",
        "娱乐" to "娱乐", "电影" to "娱乐", "游戏" to "娱乐", "旅游" to "娱乐",
        "购物" to "网购", "网购" to "网购", "淘宝" to "网购", "快递" to "网购"
    )

    fun parse(text: String): VoiceResult {
        var amount: Double? = null

        val digitPattern = Regex("""(\d+\.?\d*)\s*[元块]?""")
        val digitMatch = digitPattern.find(text)
        if (digitMatch != null) {
            amount = digitMatch.groupValues[1].toDoubleOrNull()
        }

        var categoryName: String? = null
        for ((keyword, cat) in categories) {
            if (text.contains(keyword)) {
                categoryName = cat
                break
            }
        }

        return VoiceResult(
            amount = amount,
            categoryName = categoryName,
            remark = text
        )
    }

    // 中文金额 token：用于分句与数值归一（"二十元" → "20元"）。
    private val cnAmountRegex = Regex("""([零一二两三四五六七八九十百千]+)\s*([元块])""")

    /**
     * 把一句口语化转写拆成多笔账单片段，按「金额边界」切分。
     * 例：`午餐20元打车30元` → `["午餐20元", "打车30元"]`；
     *    `早餐十块打车二十块` → `["早餐10块", "打车20块"]`（中文数字先归一为阿拉伯）。
     * 无金额的句子返回空列表。金额令牌要求带「元/块」单位，避免把日期/年份误当金额。
     */
    fun splitVoiceText(input: String): List<String> {
        val text = input.trim()
        if (text.isBlank()) return emptyList()
        val amountToken = Regex("""(?:\d+\.?\d*|[零一二两三四五六七八九十百千]+)\s*[元块]""")
        val matches = amountToken.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val segments = mutableListOf<String>()
        var prev = 0
        for (m in matches) {
            val end = m.range.last + 1
            val seg = normalizeAmounts(text.substring(prev, end)).trim()
            if (seg.isNotBlank()) segments.add(seg)
            prev = end
        }
        // 末尾无金额的残余文字（如"…记一下"）并入最后一段，保留完整备注。
        if (segments.isNotEmpty() && prev < text.length) {
            segments[segments.size - 1] = segments.last() + text.substring(prev)
        }
        return segments
    }

    /** 把带上「元/块」的中文数字归一为阿拉伯数字，如"二十块"→"20块"。 */
    private fun normalizeAmounts(seg: String): String =
        cnAmountRegex.replace(seg) { m ->
            val n = cnNumToDouble(m.groupValues[1]) ?: return@replace m.value
            "${trimPlain(n)}${m.groupValues[2]}"
        }

    /** 中文数字字符串 → Double（支持零一二两三四五六七八九、十百千万）。 */
    fun cnNumToDouble(s: String): Double? {
        if (s.isBlank()) return null
        val digits = mapOf(
            '零' to 0.0, '一' to 1.0, '二' to 2.0, '两' to 2.0, '三' to 3.0,
            '四' to 4.0, '五' to 5.0, '六' to 6.0, '七' to 7.0, '八' to 8.0, '九' to 9.0
        )
        var total = 0.0    // 已累计的高位（万以上）
        var section = 0.0  // 当前节内累积值
        var unit = 0.0     // 当前读入的一位数
        for (ch in s) {
            when (ch) {
                '零', '一', '二', '两', '三', '四', '五', '六', '七', '八', '九' -> unit = digits[ch] ?: return null
                '十' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 10; unit = 0.0 }
                '百' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 100; unit = 0.0 }
                '千' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 1000; unit = 0.0 }
                '万' -> { total += (section + unit) * 10000; section = 0.0; unit = 0.0 }
                else -> return null
            }
        }
        val result = total + section + unit
        return result.takeIf { it > 0 }
    }

    private fun trimPlain(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
