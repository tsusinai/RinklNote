package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.DailyCategoryAmount
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

    // Cleanup count of rows that still need pushing (never pushed OR locally edited/deleted).
    // Used by logout's "push-first-then-wipe" to decide whether a best-effort sync fully
    // drained the pending set before declining local data.
    @Query("SELECT COUNT(*) FROM bills WHERE server_id IS NULL OR dirty = 1")
    suspend fun countUnsynced(): Long

    // Net effect of a single account's bills: income adds, expense subtracts. Used by the
    // account-level "对账" (reconcile) so a balance can be recomputed from its own records.
    @Query(
        "SELECT SUM(CASE WHEN bill_type = 'EXPENSE' THEN -amount ELSE amount END) " +
            "FROM bills WHERE deleted = 0 AND account_id = :accountId"
    )
    suspend fun getAccountNet(accountId: Long): Double?

    @Query("UPDATE bills SET dirty = 1, deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAt: Long)

    // Unsynced = never pushed (no server_id) OR locally edited/deleted (dirty).
    // Deleted rows must still be pushed so the server soft-deletes.
    @Query("SELECT * FROM bills WHERE server_id IS NULL OR dirty = 1")
    suspend fun getUnsynced(): List<Bill>

    @Query("SELECT * FROM bills WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): Bill?

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): Bill?


    @Query("SELECT * FROM bills WHERE deleted = 0 AND date >= :dayStart AND date < :dayEnd ORDER BY date DESC")
    suspend fun getBillsByDay(dayStart: Long, dayEnd: Long): List<Bill>

    @Query("SELECT category_name, SUM(amount) as total FROM bills WHERE deleted = 0 AND bill_type = 'EXPENSE' AND date >= :dayStart AND date < :dayEnd GROUP BY category_name ORDER BY total DESC")
    suspend fun getDailyExpenseSummary(dayStart: Long, dayEnd: Long): List<DailyCategoryAmount>

    @Query("SELECT category_name, SUM(amount) as total FROM bills WHERE deleted = 0 AND bill_type = 'INCOME' AND date >= :dayStart AND date < :dayEnd GROUP BY category_name ORDER BY total DESC")
    suspend fun getDailyIncomeSummary(dayStart: Long, dayEnd: Long): List<DailyCategoryAmount>

    @Query("SELECT COUNT(*) FROM bills WHERE deleted = 0 AND date >= :dayStart AND date < :dayEnd")
    suspend fun getDailyBillCount(dayStart: Long, dayEnd: Long): Int

    @Query("UPDATE bills SET server_id = :serverId, updated_at = :updatedAt, base_updated_at = :updatedAt, dirty = 0 WHERE id = :localId")

    suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)
}
