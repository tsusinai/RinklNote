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
    private val cipher = TokenCipher()

    // In-memory cache of the decrypted token so the OkHttp interceptor does not
    // hit DataStore + Keystore on every request. Invalidated on write/clear.
    @Volatile
    private var cachedToken: String? = null

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("jwt_token")
        private val KEY_USER_ID = longPreferencesKey("user_id")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_time")
        private val KEY_QQ_BOUND = stringPreferencesKey("qq_number")
    }

    // Stored value is ciphertext; decrypt on read. Undecryptable tokens (e.g. the
    // Keystore key was lost) surface as null → treated as logged out.
    val token: Flow<String?> = context.dataStore.data.map {
        it[KEY_TOKEN]?.let { enc -> cipher.decrypt(enc) }
    }
    val userId: Flow<Long> = context.dataStore.data.map { it[KEY_USER_ID] ?: -1L }
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

    suspend fun saveAuth(token: String, userId: Long) {
        context.dataStore.edit {
            it[KEY_TOKEN] = cipher.encrypt(token)
            it[KEY_USER_ID] = userId
        }
        cachedToken = token
    }

    /**
     * 清掉旧版「QQ 号本地缓存」键（Phase D 遗留收敛：只清理不迁移）。
     * 机器人绑定状态一律以服务端 /api/{channel}-bot/bind-status 为准，本地不再缓存。
     */
    suspend fun clearLegacyQQCache() {
        context.dataStore.edit { it.remove(KEY_QQ_BOUND) }
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
        cachedToken = null
    }

    suspend fun isLoggedIn(): Boolean = context.dataStore.data.first()[KEY_TOKEN] != null

    /** Synchronous fallback for OkHttp interceptor (runs on background thread) */
    fun getTokenSync(): String? {
        cachedToken?.let { return it }
        val token = runBlocking {
            context.dataStore.data.first()[KEY_TOKEN]?.let { cipher.decrypt(it) }
        }
        cachedToken = token
        return token
    }
}
