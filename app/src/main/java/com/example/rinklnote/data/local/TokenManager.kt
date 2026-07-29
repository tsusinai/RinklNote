package com.example.rinklnote.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rinklnote_prefs")

class TokenManager(private val context: Context) {
    companion object {
        private val KEY_TOKEN = stringPreferencesKey("jwt_token")
        private val KEY_USER_ID = longPreferencesKey("user_id")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_time")
        private val KEY_QQ_BOUND = stringPreferencesKey("qq_number")
    }

    val token: Flow<String?> = context.dataStore.data.map { it[KEY_TOKEN] }
    val userId: Flow<Long> = context.dataStore.data.map { it[KEY_USER_ID] ?: -1L }
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }
    val qqNumber: Flow<String?> = context.dataStore.data.map { it[KEY_QQ_BOUND] }

    suspend fun saveAuth(token: String, userId: Long) {
        context.dataStore.edit {
            it[KEY_TOKEN] = token
            it[KEY_USER_ID] = userId
        }
    }

    suspend fun saveQQ(qqNumber: String) {
        context.dataStore.edit { it[KEY_QQ_BOUND] = qqNumber }
    }

    suspend fun setLastSyncTime(time: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNC] = time }
    }

    suspend fun clearAuth() {
        context.dataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_USER_ID)
            it.remove(KEY_QQ_BOUND)
            it.remove(KEY_LAST_SYNC)
        }
    }

    suspend fun isLoggedIn(): Boolean = context.dataStore.data.first()[KEY_TOKEN] != null

    suspend fun isQQBound(): Boolean = context.dataStore.data.first()[KEY_QQ_BOUND] != null

    /** Synchronous fallback for OkHttp interceptor (runs on background thread) */
    fun getTokenSync(): String? = runBlocking { context.dataStore.data.first()[KEY_TOKEN] }
}
