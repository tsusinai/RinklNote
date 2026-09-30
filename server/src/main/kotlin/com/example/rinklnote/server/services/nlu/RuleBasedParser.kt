package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.UserKeyword

/**
 * 关键词命中明细（2026-10-01 Task 3 建议可信度）：
 * 供 NLU 标注建议来源（历史统计 / 规则）与生成简短中文解释。
 */
data class KeywordMatch(
    val categoryName: String,
    /** 实际命中的关键词（如「午餐」「瑞幸」）。 */
    val keyword: String,
    /** 是否来自用户词表（true=用户自定义/历史学习；false=系统规则词表）。 */
    val fromUser: Boolean,
    /** 用户词表优先级（仅 fromUser=true 时有意义：≥10 为手动添加，<10 为历史学习沉淀）。 */
    val priority: Int = 0
)

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
            "工资" to "工资", "奖金" to "工资", "兼职" to "兼职", "理财" to "理财",
            // 常见品牌 → 类目映射（2026-09-18 Task 1.3）：只追加到词表尾部，不改既有键序（只增不改）；
            // 词表未覆盖的品牌仍由 LLM 兜底归类。
            "瑞幸" to "三餐", "星巴克" to "三餐", "蜜雪冰城" to "三餐", "库迪" to "三餐",
            "麦当劳" to "三餐", "肯德基" to "三餐", "汉堡王" to "三餐", "必胜客" to "三餐",
            "海底捞" to "三餐", "喜茶" to "三餐", "奈雪" to "三餐", "美团外卖" to "三餐", "饿了么" to "三餐",
            "滴滴" to "交通", "哈啰" to "交通", "高德" to "交通",
            "盒马" to "日用", "山姆" to "日用", "沃尔玛" to "日用", "大润发" to "日用", "永辉" to "日用", "名创优品" to "日用",
            "屈臣氏" to "美妆个护", "丝芙兰" to "美妆个护", "完美日记" to "美妆个护",
            "京东" to "网购", "拼多多" to "网购", "天猫" to "网购", "唯品会" to "网购",
            "优衣库" to "服饰",
            "猫眼" to "娱乐", "哔哩哔哩" to "娱乐", "B站" to "娱乐", "爱奇艺" to "娱乐",
            "腾讯视频" to "娱乐", "网易云" to "娱乐", "QQ音乐" to "娱乐", "Steam" to "娱乐",
            "小米" to "数码", "华为" to "数码",
            "携程" to "旅行", "去哪儿" to "旅行"
        )
    }

    /**
     * 与 [parse] 完全同一匹配顺序（用户词表优先 → 系统词表），但额外返回命中明细，
     * 供建议可信度标注来源与解释；未命中返回 null。
     */
    fun parseMatch(text: String, userKeywords: List<UserKeyword>): KeywordMatch? {
        // Step 1: user custom keywords (already sorted by priority descending)
        for (uk in userKeywords) {
            if (text.contains(uk.keyword)) {
                return KeywordMatch(uk.categoryName, uk.keyword, fromUser = true, priority = uk.priority)
            }
        }

        // Step 2: system default keywords
        for ((keyword, category) in SYSTEM_KEYWORDS) {
            if (text.contains(keyword)) {
                return KeywordMatch(category, keyword, fromUser = false)
            }
        }

        return null
    }

    /**
     * Try to match a category name from the input text.
     * User keywords (sorted by priority descending) take precedence over system keywords.
     * Returns the matched category name, or null if nothing matched.
     */
    fun parse(text: String, userKeywords: List<UserKeyword>): String? =
        parseMatch(text, userKeywords)?.categoryName
}
