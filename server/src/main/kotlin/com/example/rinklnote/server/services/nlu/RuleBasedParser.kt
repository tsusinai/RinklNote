package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.UserKeyword

class RuleBasedParser {
    companion object {
        val SYSTEM_KEYWORDS = mapOf(
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
            "工资" to "工资", "奖金" to "工资", "兼职" to "兼职", "理财" to "理财"
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
