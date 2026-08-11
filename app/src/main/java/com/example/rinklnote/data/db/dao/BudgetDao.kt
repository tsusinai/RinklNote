package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.rinklnote.data.db.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Upsert
    suspend fun upsert(budget: Budget)

    @Query("SELECT * FROM budgets ORDER BY month_start DESC")
    fun observeAll(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE month_start = :monthStart AND deleted = 0")
    suspend fun getByMonth(monthStart: Long): Budget?

    @Query("SELECT * FROM budgets WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): Budget?

    @Query("SELECT * FROM budgets WHERE server_id IS NULL OR dirty = 1")
    suspend fun getUnsynced(): List<Budget>

    @Query("DELETE FROM budgets WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    @Query("UPDATE budgets SET server_id = :serverId, updated_at = :updatedAt, dirty = 0 WHERE id = :localId")
    suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)
}
