package com.example.rinklnote.server.services.nlu

/**
 * 模糊金额区间解析（2026-09-18 Task 1.3 NLU 理解升级）。
 *
 * 口语金额词表（命中返回闭区间，单位元；调用方取中值落账并在回执标注区间）：
 *  - 「三四十」类（数字-数字-十，且第二位 > 第一位）→ [30,40]；**故意要求第二位更大**，
 *    避免「四十五/四十三」这类精确中文数字被误当区间（它们走既有 cnNumToDouble 精确路径）；
 *  - 「二十几」→ [20,29]；「几十（块）」→ [20,90]；
 *  - 「一百多」→ [100,199]；「百来（块）」→ [80,120]；「千把（块）」→ [900,1200]。
 * 纯函数，可单测。
 */
internal object FuzzyAmount {

    /** 解析模糊区间；未命中返回 null。 */
    fun parse(text: String): Pair<Double, Double>? {
        // 1) 「三四十」：数字+数字+十（三四十=30~40；「四十五」=45 是 数字-十-数字，不会命中）
        Regex("([一二两三四五六七八九])([一二三四五六七八九])十\\s*[块元圆]?").find(text)?.let { m ->
            val a = digit(m.groupValues[1][0])
            val b = digit(m.groupValues[2][0])
            if (a != null && b != null && b > a) return (a * 10).toDouble() to (b * 10).toDouble()
        }
        // 2) 「二十几」
        Regex("([一二两三四五六七八九])十几\\s*[块元圆]?").find(text)?.let { m ->
            val x = digit(m.groupValues[1][0])
            if (x != null) return (x * 10).toDouble() to (x * 10 + 9).toDouble()
        }
        // 3) 「几十（块/元）」——**必须带单位**：裸「几十」会命中「几十天/几十次」这类
        //    非金额语境，经路由落到记账分支后按中值 55 元 + 默认分类误落账
        if (Regex("几十\\s*[块元圆]").containsMatchIn(text)) return 20.0 to 90.0
        // 4) 「一百多」
        Regex("([一二两三四五六七八九])百多\\s*[块元圆]?").find(text)?.let { m ->
            val x = digit(m.groupValues[1][0])
            if (x != null) return (x * 100).toDouble() to (x * 100 + 99).toDouble()
        }
        // 5) 「百来块」
        if (Regex("百来\\s*[块元圆]?").containsMatchIn(text)) return 80.0 to 120.0
        // 6) 「千把块」
        if (Regex("千把\\s*[块元圆]?").containsMatchIn(text)) return 900.0 to 1200.0
        return null
    }

    /** 区间中值（落账金额）。 */
    fun mid(range: Pair<Double, Double>): Double = (range.first + range.second) / 2.0

    /** 回执标注文案：如「区间30~40元，按中值35元记」。 */
    fun note(range: Pair<Double, Double>): String {
        val mid = mid(range)
        val midText = if (mid == mid.toLong().toDouble()) mid.toLong().toString() else mid.toString()
        return "区间${range.first.toInt()}~${range.second.toInt()}元，按中值${midText}元记"
    }

    private fun digit(ch: Char): Int? = when (ch) {
        '一' -> 1
        '两', '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> null
    }
}
