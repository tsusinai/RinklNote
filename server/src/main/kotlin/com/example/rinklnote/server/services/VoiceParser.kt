package com.example.rinklnote.server.services

data class VoiceResult(
    val amount: Double?,
    val categoryName: String?,
    val remark: String
)

data class UserKeyword(
    val keyword: String,
    val categoryName: String
)

object VoiceParser {
    private val defaultCategories = mapOf(
        "餐" to "三餐", "饭" to "三餐", "吃" to "三餐", "早餐" to "三餐",
        "午餐" to "三餐", "晚餐" to "三餐", "零食" to "三餐",
        "日用" to "日用", "超市" to "日用", "百货" to "日用",
        "交通" to "交通", "打车" to "交通", "地铁" to "交通", "公交" to "交通", "加油" to "交通",
        "学习" to "学习", "书" to "学习", "教育" to "学习", "培训" to "学习",
        "运动" to "运动", "健身" to "运动",
        "娱乐" to "娱乐", "电影" to "娱乐", "游戏" to "娱乐", "旅游" to "娱乐",
        "购物" to "网购", "网购" to "网购", "淘宝" to "网购", "快递" to "网购"
    )

    private val digitPattern = Regex("""(\d+\.?\d*)\s*[元块]?""")

    fun parse(text: String, userKeywords: List<UserKeyword> = emptyList()): VoiceResult {
        var amount: Double? = null

        val digitMatch = digitPattern.find(text)
        if (digitMatch != null) {
            amount = digitMatch.groupValues[1].toDoubleOrNull()
        }

        var categoryName: String? = null

        // User custom keywords first (higher priority)
        for (uk in userKeywords) {
            if (text.contains(uk.keyword)) {
                categoryName = uk.categoryName
                break
            }
        }

        // Fall back to system defaults
        if (categoryName == null) {
            for ((keyword, cat) in defaultCategories) {
                if (text.contains(keyword)) {
                    categoryName = cat
                    break
                }
            }
        }

        return VoiceResult(amount = amount, categoryName = categoryName, remark = text)
    }
}
