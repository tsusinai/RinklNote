package com.example.rinklnote.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "rinklnote_settings")

class SettingsManager(private val context: Context) {
    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        private val KEY_BACKGROUND_URI = stringPreferencesKey("background_uri")
        private val KEY_BALANCE_HIDDEN = booleanPreferencesKey("balance_hidden")
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

    /** 余额/金额隐私掩码：默认 false（显示真实金额）。App 与主屏小组件共读此开关。 */
    val balanceHidden: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_BALANCE_HIDDEN] ?: false }

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

    suspend fun setBalanceHidden(hidden: Boolean) {
        context.settingsDataStore.edit { it[KEY_BALANCE_HIDDEN] = hidden }
    }
}
