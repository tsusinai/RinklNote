package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.UserKeyword

class RuleBasedParser {
    companion object {
        val SYSTEM_KEYWORDS = mapOf(
            "餐" to "三餐", "饭" to "三餐", "吃" to "三餐", "早餐" to "三餐",
            "午餐" to "三餐", "晚餐" to "三餐", "零食" to "三餐",
            "日用" to "日用", "超市" to "日用", "百货" to "日用",
            "交通" to "交通", "打车" to "交通", "地铁" to "交通", "公交" to "交通", "加油" to "交通",
            "学习" to "学习", "书" to "学习", "教育" to "学习", "培训" to "学习",
            "运动" to "运动", "健身" to "运动",
            "娱乐" to "娱乐", "电影" to "娱乐", "游戏" to "娱乐", "旅游" to "娱乐",
            "购物" to "网购", "网购" to "网购", "淘宝" to "网购", "快递" to "网购",
            "工资" to "工资", "兼职" to "兼职", "理财" to "理财"
        )
    }

    /**
     * Try to match a category name from the input text.
     * User keywords (sorted by priority DESC) take precedence over system keywords.
     * Returns the matched category name, or null if nothing matched.
     */
    fun parse(text: String, userKeywords: List<UserKeyword>): String? {
        // Step 1: user custom keywords (already sorted by priority descending)
        for (uk in userKeywords) {
            if (text.contains(uk.keyword)) {
                return uk.categoryName
            }
        }

        // Step 2: system default keywords
        for ((keyword, category) in SYSTEM_KEYWORDS) {
            if (text.contains(keyword)) {
                return category
            }
        }

        return null
    }
}
