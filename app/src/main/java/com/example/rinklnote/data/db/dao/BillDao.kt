package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.Bill
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Insert
    suspend fun insert(bill: Bill): Long

    @Update
    suspend fun update(bill: Bill)

    @Query("SELECT * FROM bills WHERE deleted = 0 ORDER BY date DESC, created_at DESC")
    fun observeAll(): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE deleted = 0 AND date >= :monthStart AND date < :nextMonthStart ORDER BY date DESC")
    fun observeByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>>

    @Query("SELECT SUM(amount) FROM bills WHERE deleted = 0 AND bill_type = 'EXPENSE' AND date >= :monthStart AND date < :nextMonthStart")
    suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double?

    @Query("SELECT SUM(amount) FROM bills WHERE deleted = 0 AND bill_type = 'INCOME' AND date >= :monthStart AND date < :nextMonthStart")
    suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double?

    @androidx.room.Upsert
    suspend fun upsertAll(bills: List<Bill>)

    @Query("DELETE FROM bills WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun hardDeleteById(id: Long)

    @Query("DELETE FROM bills")
    suspend fun deleteAll()

    // Logout cleanup: purge only bills already pushed to the server (server_id set)
    // AND not locally edited (dirty = 0). Locally-edited / never-pushed rows are kept
    // so they are not permanently lost and get pushed on the next login's sync.
    @Query("DELETE FROM bills WHERE server_id IS NOT NULL AND dirty = 0")
    suspend fun deleteSynced()

    @Query("UPDATE bills SET dirty = 1, deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAt: Long)

    // Unsynced = never pushed (no server_id) OR locally edited/deleted (dirty).
    // Deleted rows must still be pushed so the server soft-deletes.
    @Query("SELECT * FROM bills WHERE server_id IS NULL OR dirty = 1")
    suspend fun getUnsynced(): List<Bill>

    @Query("SELECT * FROM bills WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): Bill?

    @Query("UPDATE bills SET server_id = :serverId, updated_at = :updatedAt, base_updated_at = :updatedAt, dirty = 0 WHERE id = :localId")
    suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)
}
