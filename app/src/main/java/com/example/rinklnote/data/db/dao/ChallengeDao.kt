package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.rinklnote.data.db.entity.Challenge
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeDao {
    @Upsert
    suspend fun upsert(challenge: Challenge)

    @Query("SELECT * FROM challenges ORDER BY period_start DESC, id DESC")
    fun observeAll(): Flow<List<Challenge>>

    // 注意：这里不带 deleted = 0 过滤——upsertActive 需要复活同 scope 的软删行，
    // 且服务端 scope 唯一键（user_id + type + period_start）同样跨 deleted 复活；
    // 若过滤掉软删行，会另建新行导致同步后本地出现两条同 scope 记录。
    @Query("SELECT * FROM challenges WHERE type = :type AND period_start = :periodStart LIMIT 1")
    suspend fun getByScope(type: String, periodStart: Long): Challenge?

    @Query("SELECT * FROM challenges WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): Challenge?

    // Unsynced = 从未推送过（无 server_id）或本地有未推送的编辑/删除。
    @Query("SELECT * FROM challenges WHERE server_id IS NULL OR dirty = 1")
    suspend fun getUnsynced(): List<Challenge>

    @Query("DELETE FROM challenges WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    @Query("DELETE FROM challenges WHERE id = :id")
    suspend fun hardDeleteById(id: Long)

    @Query("DELETE FROM challenges")
    suspend fun deleteAll()

    @Query("UPDATE challenges SET server_id = :serverId, updated_at = :updatedAt, dirty = 0 WHERE id = :localId")
    suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)

    // 软删除：保留行以推送墓碑（与 bills.softDelete 同语义）。
    @Query("UPDATE challenges SET dirty = 1, deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAt: Long)

    // 定向改目标 / 改状态：置 dirty = 1 交 SyncManager 推送（updatedAt 由调用方传 now）。
    @Query("UPDATE challenges SET goal = :goal, dirty = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateGoal(id: Long, goal: Long, updatedAt: Long)

    @Query("UPDATE challenges SET status = :status, dirty = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)
}
