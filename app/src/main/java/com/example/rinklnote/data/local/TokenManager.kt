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

/**
 * 最近一次同步失败的可恢复信息（体验优化 Task 1）：仅失败时间戳与**用户可读**的失败原因。
 * 红线：绝不写入 token 或任何账单内容；退出登录随 [TokenManager.clearAuth] 一并清除。
 */
data class LastSyncFailure(val time: Long, val reason: String)

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
        // 同步失败可恢复信息（体验优化 Task 1）：与 token / 账单完全分离的独立键。
        private val KEY_LAST_FAIL_TIME = longPreferencesKey("last_sync_fail_time")
        private val KEY_LAST_FAIL_REASON = stringPreferencesKey("last_sync_fail_reason")
    }

    // Stored value is ciphertext; decrypt on read. Undecryptable tokens (e.g. the
    // Keystore key was lost) surface as null → treated as logged out.
    val token: Flow<String?> = context.dataStore.data.map {
        it[KEY_TOKEN]?.let { enc -> cipher.decrypt(enc) }
    }
    val userId: Flow<Long> = context.dataStore.data.map { it[KEY_USER_ID] ?: -1L }
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

    /** 最近一次同步失败（时间 + 用户可读原因）；无失败记录时为 null。进程重启后仍可读。 */
    val lastSyncFailure: Flow<LastSyncFailure?> = context.dataStore.data.map { prefs ->
        val time = prefs[KEY_LAST_FAIL_TIME] ?: 0L
        val reason = prefs[KEY_LAST_FAIL_REASON]
        if (time > 0L && reason != null) LastSyncFailure(time, reason) else null
    }

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

    /** 记录一次同步失败：时间取当前时刻，[reason] 为用户可读文案（不含任何敏感内容）。 */
    suspend fun setSyncFailure(reason: String) {
        context.dataStore.edit {
            it[KEY_LAST_FAIL_TIME] = System.currentTimeMillis()
            it[KEY_LAST_FAIL_REASON] = reason
        }
    }

    /** 清除同步失败记录（同步恢复成功时调用）。 */
    suspend fun clearSyncFailure() {
        context.dataStore.edit {
            it.remove(KEY_LAST_FAIL_TIME)
            it.remove(KEY_LAST_FAIL_REASON)
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_USER_ID)
            it.remove(KEY_QQ_BOUND)
            it.remove(KEY_LAST_SYNC)
            // 失败原因属用户数据，退出登录一并清除。
            it.remove(KEY_LAST_FAIL_TIME)
            it.remove(KEY_LAST_FAIL_REASON)
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
