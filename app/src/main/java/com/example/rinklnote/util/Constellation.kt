package com.example.rinklnote.util

/**
 * 星座推算（2026-09-17 个人资料页）：公历生日 → 星座名，纯 Kotlin 函数（无 Android 依赖，可 JVM 单测）。
 *
 * 口径（通行占星区间，公历）：
 * 摩羯 12.22-1.19 / 水瓶 1.20-2.18 / 双鱼 2.19-3.20 / 白羊 3.21-4.19 / 金牛 4.20-5.20 /
 * 双子 5.21-6.21 / 巨蟹 6.22-7.22 / 狮子 7.23-8.22 / 处女 8.23-9.22 / 天秤 9.23-10.23 /
 * 天蝎 10.24-11.22 / 射手 11.23-12.21。
 */
object Constellation {

    /** 各星座起始日的 `MMDD` 分界（每个星座「从这天开始」），与 NAMES 一一对应。 */
    private val CUTS = intArrayOf(120, 219, 321, 420, 521, 622, 723, 823, 923, 1024, 1123, 1222)

    /** CUTS 分界后的星座名；首尾都是摩羯（12.22 起 / 1.19 止）。 */
    private val NAMES = arrayOf(
        "摩羯座", "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座",
        "巨蟹座", "狮子座", "处女座", "天秤座", "天蝎座", "射手座", "摩羯座"
    )

    /** 公历月/日 → 星座名；入参非法（月不在 1..12、日不在 1..31）返回 null。 */
    fun of(month: Int, day: Int): String? {
        if (month !in 1..12 || day !in 1..31) return null
        val md = month * 100 + day
        var name = NAMES[0]
        for (i in CUTS.indices) {
            if (md >= CUTS[i]) name = NAMES[i + 1] else break
        }
        return name
    }

    /**
     * ISO 生日串（yyyy-MM-dd，与服务端 birthday 字段同形）→ 星座名。
     * 解析失败 / 空值返回 null（UI 不显示角标）。
     */
    fun ofBirthday(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val date = java.time.LocalDate.parse(iso)
            of(date.monthValue, date.dayOfMonth)
        } catch (_: Exception) {
            null
        }
    }
}
