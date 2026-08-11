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
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map {
        when (it[KEY_THEME_MODE]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val autoSync: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_AUTO_SYNC] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_SYNC] = enabled }
    }
}
