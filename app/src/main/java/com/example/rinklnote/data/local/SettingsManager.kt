package com.example.rinklnote.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.graphics.Color
import com.example.rinklnote.ui.theme.RinklThemeSlot
import com.example.rinklnote.ui.theme.colorToHex
import com.example.rinklnote.ui.theme.hexToColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "rinklnote_settings")

class SettingsManager(private val context: Context) {
    companion object {
        /** 本位币默认值：人民币（ISO 4217 代码）。 */
        const val DEFAULT_BASE_CURRENCY = "CNY"

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        private val KEY_BACKGROUND_URI = stringPreferencesKey("background_uri")
        // 多币种（预实现）：本位币，存 ISO 4217 代码（如 "CNY"）。
        private val KEY_BASE_CURRENCY = stringPreferencesKey("base_currency")
        private val KEY_BALANCE_HIDDEN = booleanPreferencesKey("balance_hidden")
        private val KEY_DAILY_REPORT_ENABLED = booleanPreferencesKey("daily_report_enabled")
        private val KEY_DAILY_REPORT_HOUR = intPreferencesKey("daily_report_hour")
        private val KEY_DAILY_REPORT_MINUTE = intPreferencesKey("daily_report_minute")
        private val KEY_DAILY_REPORT_QQ_BOT = booleanPreferencesKey("daily_report_qq_bot")
        // 个性化：自选头像与昵称（null = 未设置，头像回落首字符徽章、昵称回落手机号）。
        private val KEY_AVATAR_URI = stringPreferencesKey("avatar_uri")
        private val KEY_NICKNAME = stringPreferencesKey("nickname")
        // 展示偏好：金额货币符号开关（默认显示）；卡片白色蒙版开关（默认关闭）。
        private val KEY_SHOW_CURRENCY_SYMBOL = booleanPreferencesKey("show_currency_symbol")
        private val KEY_CARD_OVERLAY = booleanPreferencesKey("card_overlay")
        // 自定义主题：10 个颜色槽，均存 "#AARRGGBB"；缺省 = 未自定义（回落到默认色）。
        private val KEY_THEME_FONT_COLOR = stringPreferencesKey("theme_font_color")
        private val KEY_THEME_PRIMARY_COLOR = stringPreferencesKey("theme_primary_color")
        private val KEY_THEME_TOPBAR_COLOR = stringPreferencesKey("theme_topbar_color")
        private val KEY_THEME_ICON_COLOR = stringPreferencesKey("theme_icon_color")
        private val KEY_THEME_BORDER_COLOR = stringPreferencesKey("theme_border_color")
        private val KEY_THEME_HEATMAP_COLOR = stringPreferencesKey("theme_heatmap_color")
        private val KEY_THEME_CHART_COLOR = stringPreferencesKey("theme_chart_color")
        private val KEY_THEME_NAVICON_COLOR = stringPreferencesKey("theme_navicon_color")
        private val KEY_THEME_EXPENSE_COLOR = stringPreferencesKey("theme_expense_color")
        private val KEY_THEME_INCOME_COLOR = stringPreferencesKey("theme_income_color")

        /** 槽位 → DataStore 键 的映射（读写共用，避免两处各写一遍 when）。 */
        private fun keyOf(slot: RinklThemeSlot) = when (slot) {
            RinklThemeSlot.FONT -> KEY_THEME_FONT_COLOR
            RinklThemeSlot.PRIMARY -> KEY_THEME_PRIMARY_COLOR
            RinklThemeSlot.TOP_BAR -> KEY_THEME_TOPBAR_COLOR
            RinklThemeSlot.ICON -> KEY_THEME_ICON_COLOR
            RinklThemeSlot.BORDER -> KEY_THEME_BORDER_COLOR
            RinklThemeSlot.HEATMAP -> KEY_THEME_HEATMAP_COLOR
            RinklThemeSlot.CHART -> KEY_THEME_CHART_COLOR
            RinklThemeSlot.NAV_ICON -> KEY_THEME_NAVICON_COLOR
            RinklThemeSlot.EXPENSE -> KEY_THEME_EXPENSE_COLOR
            RinklThemeSlot.INCOME -> KEY_THEME_INCOME_COLOR
        }

        /** 全部自定义主题键（清空用，顺序与 keyOf 无关）。 */
        private val THEME_COLOR_KEYS = listOf(
            KEY_THEME_FONT_COLOR, KEY_THEME_PRIMARY_COLOR, KEY_THEME_TOPBAR_COLOR,
            KEY_THEME_ICON_COLOR, KEY_THEME_BORDER_COLOR,
            KEY_THEME_HEATMAP_COLOR, KEY_THEME_CHART_COLOR, KEY_THEME_NAVICON_COLOR,
            KEY_THEME_EXPENSE_COLOR, KEY_THEME_INCOME_COLOR
        )
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map {
        when (it[KEY_THEME_MODE]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val autoSync: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_AUTO_SYNC] ?: true }

    /** 自定义背景：记账页毛玻璃背后铺的图库照片路径；null 表示未设置（用主题背景色）。 */
    val backgroundUri: Flow<String?> = context.settingsDataStore.data.map { it[KEY_BACKGROUND_URI] }

    /**
     * 本位币（ISO 4217 代码，如 CNY/USD/EUR…）；默认人民币。
     * 当前仅多币种预实现页读写，暂不参与任何记账/统计换算（bills 金额仍为整数分本位币）。
     */
    val baseCurrency: Flow<String> =
        context.settingsDataStore.data.map { it[KEY_BASE_CURRENCY] ?: DEFAULT_BASE_CURRENCY }

    /** 余额/金额隐私掩码：默认 false（显示真实金额）。App 与主屏小组件共读此开关。 */
        val balanceHidden: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_BALANCE_HIDDEN] ?: false }
    val dailyReportEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_DAILY_REPORT_ENABLED] ?: false }
    val dailyReportHour: Flow<Int> = context.settingsDataStore.data.map { it[KEY_DAILY_REPORT_HOUR] ?: 9 }
    val dailyReportMinute: Flow<Int> = context.settingsDataStore.data.map { it[KEY_DAILY_REPORT_MINUTE] ?: 0 }
    val dailyReportQqBot: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_DAILY_REPORT_QQ_BOT] ?: false }

    /** 自定义主题的 10 个颜色槽；未自定义的槽不会出现在 map 里。 */
    val customThemeColors: Flow<Map<RinklThemeSlot, Color>> = context.settingsDataStore.data.map { prefs ->
        buildMap {
            RinklThemeSlot.entries.forEach { slot ->
                hexToColor(prefs[keyOf(slot)])?.let { put(slot, it) }
            }
        }
    }

    /** 写入单个槽位的自定义色；传 null 表示恢复该槽默认。 */
    suspend fun setCustomThemeColor(slot: RinklThemeSlot, color: Color?) {
        context.settingsDataStore.edit { prefs ->
            val key = keyOf(slot)
            if (color == null) prefs.remove(key) else prefs[key] = colorToHex(color)
        }
    }

    /** 一次性恢复全部槽位的默认色。 */
    suspend fun clearCustomTheme() {
        context.settingsDataStore.edit { prefs -> THEME_COLOR_KEYS.forEach { prefs.remove(it) } }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_SYNC] = enabled }
    }

    suspend fun setBackgroundUri(uri: String?) {
        context.settingsDataStore.edit {
            if (uri == null) it.remove(KEY_BACKGROUND_URI)
            else it[KEY_BACKGROUND_URI] = uri
        }
    }

    /** 写入本位币（ISO 4217 代码，由调用方保证取值合法，如多币种页的币种清单）。 */
    suspend fun setBaseCurrency(code: String) {
        context.settingsDataStore.edit { it[KEY_BASE_CURRENCY] = code }
    }

    suspend fun setDailyReportEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DAILY_REPORT_ENABLED] = enabled }
    }

    suspend fun setDailyReportTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[KEY_DAILY_REPORT_HOUR] = hour
            it[KEY_DAILY_REPORT_MINUTE] = minute
        }
    }

    suspend fun setDailyReportQqBot(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DAILY_REPORT_QQ_BOT] = enabled }
    }

    suspend fun setBalanceHidden(hidden: Boolean) {
        context.settingsDataStore.edit { it[KEY_BALANCE_HIDDEN] = hidden }
    }

    // -------------------------------------------------------------------------
    // 个性化（跨代理契约 R3-A1）：头像 / 昵称 / 货币符号开关 / 卡片白蒙版开关。
    // 消费方：ProfileScreen / ProfileCards（A2）、Money.format 与 applyCardGlass（经 DisplayPreferences 桥接）。
    // -------------------------------------------------------------------------

    /** 自选头像（图库照片的内容 URI 字符串）；null = 未设置，消费端回落「首字符徽章」。 */
    val avatarUri: Flow<String?> = context.settingsDataStore.data.map { it[KEY_AVATAR_URI] }

    /** 写入自选头像；传 null 表示清除（回到首字符徽章）。 */
    suspend fun setAvatarUri(uri: String?) {
        context.settingsDataStore.edit {
            if (uri == null) it.remove(KEY_AVATAR_URI)
            else it[KEY_AVATAR_URI] = uri
        }
    }

    /** 自定义昵称；null = 未设置，消费端按「自定义昵称 > 手机号」的优先级回落。 */
    val nickname: Flow<String?> = context.settingsDataStore.data.map { it[KEY_NICKNAME] }

    /** 写入自定义昵称；传 null 或空白串表示清除（回落手机号）。 */
    suspend fun setNickname(name: String?) {
        context.settingsDataStore.edit {
            val trimmed = name?.trim()
            if (trimmed.isNullOrEmpty()) it.remove(KEY_NICKNAME)
            else it[KEY_NICKNAME] = trimmed
        }
    }

    /** 金额货币符号开关：true（默认）= Money.format 输出带 ¥；false = 与 formatPlain 同观感（不带 ¥）。 */
    val showCurrencySymbol: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_SHOW_CURRENCY_SYMBOL] ?: true }

    suspend fun setShowCurrencySymbol(visible: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_CURRENCY_SYMBOL] = visible }
    }

    /** 卡片白色蒙版开关：true = applyCardGlass 在边框前铺一层雾蒙版（提高照片底上的可读性）；false（默认）= 现状行为。 */
    val cardOverlay: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_CARD_OVERLAY] ?: false }

    suspend fun setCardOverlay(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_CARD_OVERLAY] = enabled }
    }
}
