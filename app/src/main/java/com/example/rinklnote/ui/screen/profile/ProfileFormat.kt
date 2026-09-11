package com.example.rinklnote.ui.screen.profile

import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 「我的」页的纯格式化函数集合（无 Compose 依赖，便于单测）。
 * 2026-09-11 重构：原先散落在 ProfileScreen.kt 底部，与页面组件混在一起。
 */

/** 主题模式的中文标签（设置行右侧值 / 主题选择弹窗共用同一份文案）。 */
internal fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

/** 手机号打码：保留前 3 位与后 4 位，中间替换为 `****`。长度不足时原样返回。 */
internal fun maskPhone(phone: String): String =
    if (phone.length >= 7) phone.replaceRange(3, 7, "****") else phone

/** 上次同步时间（epoch millis）→ `MM-dd HH:mm`；从未同步（<=0）返回「从未」。 */
internal fun formatSyncTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "从未"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(bookkeepingZone())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
