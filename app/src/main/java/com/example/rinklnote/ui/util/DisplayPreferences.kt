package com.example.rinklnote.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 展示偏好的进程内单例（仿 [BalancePrivacy] 的全局态模式）——承载需要在
 * **非组合上下文**同步读取的两个开关：
 *
 * - [currencySymbolVisible]：金额货币符号开关。`Money.format` 是普通函数（大量调用点在
 *   ViewModel / Canvas 绘制等非 @Composable 场景），无法 collect Flow，只能同步读内存值。
 * - [cardOverlayEnabled]：卡片白色蒙版开关。毛玻璃蒙版挂在 Modifier 链里，可组合读取，
 *   与货币符号共用同一桥接通道，保持一处同步。
 *
 * 持久化桥接由 `MainActivity` 注入：collect `SettingsManager.showCurrencySymbol` /
 * `cardOverlay` 两个 DataStore 流，变化时经 [setCurrencySymbolVisible] / [setCardOverlay]
 * 同步进内存（方向是单向的 DataStore → 内存，本对象不回写，避免写回循环）。
 *
 * 默认值与 DataStore 缺省值一致：货币符号显示（true）、卡片蒙版关闭（false）——
 * 进程刚启动、DataStore 尚未吐出首值时，行为与「不动设置」完全一致。
 */
object DisplayPreferences {

    /** 金额是否带货币符号（¥）。默认 true = 显示；false = Money.format 输出与 formatPlain 同观感。 */
    private val _currencySymbolVisible = MutableStateFlow(true)
    val currencySymbolVisible: StateFlow<Boolean> = _currencySymbolVisible.asStateFlow()

    /** 卡片白色蒙版是否开启。默认 false = 关闭（applyCardGlass 保持既有行为）。 */
    private val _cardOverlayEnabled = MutableStateFlow(false)
    val cardOverlayEnabled: StateFlow<Boolean> = _cardOverlayEnabled.asStateFlow()

    /** 内部写入口（仅供 MainActivity 的持久化桥接调用）：同步货币符号开关。 */
    fun setCurrencySymbolVisible(visible: Boolean) {
        _currencySymbolVisible.value = visible
    }

    /** 内部写入口（仅供 MainActivity 的持久化桥接调用）：同步卡片蒙版开关。 */
    fun setCardOverlay(enabled: Boolean) {
        _cardOverlayEnabled.value = enabled
    }
}
