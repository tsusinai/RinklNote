package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: Account): Long

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY id ASC")
    suspend fun getAllActive(): List<Account>

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY id ASC")
    fun observeAll(): Flow<List<Account>>

    @Update
    suspend fun update(account: Account)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT * FROM accounts WHERE server_id IS NULL OR dirty = 1")
    suspend fun getUnsynced(): List<Account>

    @Query("SELECT * FROM accounts WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): Account?

    // Reconcile a local seeded/custom account (no server_id yet) with a server
    // account of the same name, so it adopts the server id instead of becoming a
    // duplicate. Only targets rows not yet stamped with a server id.
    @Query("SELECT * FROM accounts WHERE deleted = 0 AND name = :name AND server_id IS NULL ORDER BY id ASC LIMIT 1")
    suspend fun getByNameActive(name: String): Account?

    // 按名称取活动账户（不限 server_id）。用于把既有种子「默认」改名为「无账户」桶，
    // 无论它是否已同步（server_id 可能已落库）。只为存储安全，不作为货币/余额。
    @Query("SELECT * FROM accounts WHERE deleted = 0 AND name = :name ORDER BY id ASC LIMIT 1")
    suspend fun getActiveByName(name: String): Account?

    @androidx.room.Upsert
    suspend fun upsert(account: Account)

    @Query("DELETE FROM accounts WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun hardDeleteById(id: Long)

    @Query("UPDATE accounts SET server_id = :serverId, updated_at = :updatedAt, dirty = 0 WHERE id = :localId")
    suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)

    @Query("UPDATE accounts SET dirty = 1, deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAt: Long)

    // Cleanup count of accounts still needing push; used by logout's push-first-then-wipe.
    @Query("SELECT COUNT(*) FROM accounts WHERE server_id IS NULL OR dirty = 1")
    suspend fun countUnsynced(): Long

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}
