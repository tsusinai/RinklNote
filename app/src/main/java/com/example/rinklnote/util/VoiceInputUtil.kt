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
    // 关键词按「先长后短、先具体后宽泛」排列（contains 顺序命中）；
    // 分类名必须与 BillRepositoryImpl.seedCategories 的清单逐字一致。
    private val categories = mapOf(
        "餐" to "三餐", "饭" to "三餐", "吃" to "三餐", "早餐" to "三餐",
        "午餐" to "三餐", "晚餐" to "三餐", "零食" to "三餐", "外卖" to "三餐", "饮品" to "三餐",
        "日用" to "日用", "超市" to "日用", "百货" to "日用",
        "交通" to "交通", "打车" to "交通", "地铁" to "交通", "公交" to "交通", "加油" to "交通",
        "停车费" to "交通", "火车机票" to "交通", "共享单车" to "交通",
        "学习" to "学习", "书" to "学习", "教育" to "学习", "培训" to "学习",
        "运动" to "运动", "健身" to "运动",
        "娱乐" to "娱乐", "电影" to "娱乐", "游戏" to "娱乐", "旅游" to "娱乐", "演出" to "娱乐",
        "购物" to "网购", "网购" to "网购", "淘宝" to "网购", "快递" to "网购",
        "医院" to "医疗", "看病" to "医疗", "挂号" to "医疗", "体检" to "医疗", "药" to "医疗",
        "房租" to "居家", "房贷" to "居家", "物业" to "居家", "水电" to "居家", "燃气" to "居家",
        "宽带" to "居家", "网费" to "居家",
        "随礼" to "人情", "份子" to "人情", "礼物" to "人情", "请客" to "人情", "人情" to "人情",
        "猫粮" to "宠物", "狗粮" to "宠物", "宠物" to "宠物",
        "理发" to "美妆个护", "化妆" to "美妆个护", "护肤" to "美妆个护", "美容" to "美妆个护",
        "衣服" to "服饰", "服装" to "服饰", "鞋" to "服饰",
        "奶粉" to "母婴", "尿布" to "母婴", "玩具" to "母婴",
        "保养" to "汽车", "洗车" to "汽车", "车位" to "汽车", "油费" to "汽车",
        "手机" to "数码", "电脑" to "数码", "耳机" to "数码", "平板" to "数码", "数码" to "数码",
        "保险" to "保险", "保费" to "保险", "社保" to "保险", "医保" to "保险",
        "机票" to "旅行", "酒店" to "旅行", "民宿" to "旅行", "门票" to "旅行", "签证" to "旅行",
        "报销" to "报销",
        "转卖" to "二手转卖", "闲鱼" to "二手转卖", "二手" to "二手转卖", "出售" to "二手转卖",
        "压岁钱" to "红包礼金", "红包" to "红包礼金", "礼金" to "红包礼金",
        "工资" to "工资", "奖金" to "工资", "兼职" to "兼职", "理财" to "理财",
        "利息" to "理财", "基金" to "理财", "股票" to "理财"
    )

    fun parse(text: String): VoiceResult {
        // 金额提取：优先「带单位的阿拉伯数字」，其次「带单位的中文数字（大写/小写）」，最后兜底裸阿拉伯数字。
        // 带单位优先可避免把"8月"这类日期数字误当金额。
        var amount: Double? = Regex("""(\d+\.?\d*)\s*[元圆块]""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        if (amount == null) {
            amount = Regex("""([${CN_NUM_CHARS}]+)\s*[元圆块]""").find(text)
                ?.let { cnNumToDouble(it.groupValues[1]) }
        }
        if (amount == null) {
            amount = Regex("""(\d+\.?\d*)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
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

    // 中文数字字符集（小写 + 大写财务数字 + 零/两/廿），用于正则匹配与数值归一。
    private const val CN_NUM_CHARS = "零〇一二两廿三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"

    // 中文金额 token：用于分句与数值归一（"二十元" → "20元"，"贰拾圆" → "20圆"）。
    private val cnAmountRegex = Regex("""([${CN_NUM_CHARS}]+)\s*([元圆块])""")

    /**
     * 把一句口语化转写拆成多笔账单片段，按「金额边界」切分。
     * 例：`午餐20元打车30元` → `["午餐20元", "打车30元"]`；
     *    `早餐十块打车二十块` → `["早餐10块", "打车20块"]`（中文数字先归一为阿拉伯）；
     *    `午餐贰拾八元打车拾伍圆` → `["午餐28元", "打车15圆"]`（大写/小写混用同样归一）。
     * 无金额的句子返回空列表。金额令牌要求带「元/圆/块」单位，避免把日期/年份误当金额。
     */
    fun splitVoiceText(input: String): List<String> {
        val text = input.trim()
        if (text.isBlank()) return emptyList()
        val amountToken = Regex("""(?:\d+\.?\d*|[${CN_NUM_CHARS}]+)\s*[元圆块]""")
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

    /** 把带上「元/圆/块」的中文数字归一为阿拉伯数字，如"二十块"→"20块"，"贰拾圆"→"20圆"。 */
    private fun normalizeAmounts(seg: String): String =
        cnAmountRegex.replace(seg) { m ->
            val n = cnNumToDouble(m.groupValues[1]) ?: return@replace m.value
            "${trimPlain(n)}${m.groupValues[2]}"
        }

    /**
     * 中文数字字符串 → Double。支持大写财务数字（壹贰叁肆伍陆柒捌玖拾佰仟万亿）与小写
     * （一二两三四五六七八九十百千万）混用，含 零/〇/两/廿。如"二十"=20、"一百二十五"=125、
     * "贰拾八"=28、"壹佰零伍"=105、"廿五"=25。仅整数元（不含角/分），无法识别返回 null。
     */
    fun cnNumToDouble(s: String): Double? {
        if (s.isBlank()) return null
        val digits = mapOf(
            '零' to 0.0, '〇' to 0.0, '一' to 1.0, '壹' to 1.0, '二' to 2.0, '贰' to 2.0, '两' to 2.0,
            '三' to 3.0, '叁' to 3.0, '四' to 4.0, '肆' to 4.0, '五' to 5.0, '伍' to 5.0,
            '六' to 6.0, '陆' to 6.0, '七' to 7.0, '柒' to 7.0, '八' to 8.0, '捌' to 8.0,
            '九' to 9.0, '玖' to 9.0
        )
        var total = 0.0    // 已累计的高位（万/亿以上）
        var section = 0.0  // 当前节内累积值
        var unit = 0.0     // 当前读入的一位数
        for (ch in s) {
            when (ch) {
                '廿' -> { section += 20; unit = 0.0 }                          // 廿 = 20（廿五 = 25）
                '亿' -> { total += (section + unit) * 100_000_000; section = 0.0; unit = 0.0 }
                '万' -> { total += (section + unit) * 10_000; section = 0.0; unit = 0.0 }
                '十', '拾' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 10; unit = 0.0 }
                '百', '佰' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 100; unit = 0.0 }
                '千', '仟' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 1000; unit = 0.0 }
                else -> { unit = digits[ch] ?: return null }
            }
        }
        val result = total + section + unit
        return result.takeIf { it > 0 }
    }

    private fun trimPlain(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
