package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.DailyCategoryAmount
import com.example.rinklnote.data.db.entity.DailySpendStat
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Insert
    suspend fun insert(bill: Bill): Long

    @Update
    suspend fun update(bill: Bill)

    // 排序：日倒序；日内按显式 sort_order 降序，未排序（NULL）按 created_at 兜底（新账单在前）。
    @Query("SELECT * FROM bills WHERE deleted = 0 ORDER BY date DESC, COALESCE(sort_order, created_at) DESC, created_at DESC")
    fun observeAll(): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE deleted = 0 AND date >= :monthStart AND date < :nextMonthStart ORDER BY date DESC, COALESCE(sort_order, created_at) DESC, created_at DESC")
    fun observeByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>>

    @Query("SELECT SUM(amount_minor) FROM bills WHERE deleted = 0 AND bill_type = 'EXPENSE' AND date >= :monthStart AND date < :nextMonthStart")
    suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Long?

    @Query("SELECT SUM(amount_minor) FROM bills WHERE deleted = 0 AND bill_type = 'INCOME' AND date >= :monthStart AND date < :nextMonthStart")
    suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Long?

    @Query("SELECT SUM(amount_minor) FROM bills WHERE deleted = 0 AND bill_type = 'EXPENSE' AND date >= :monthStart AND date < :nextMonthStart")
    fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Long?>

    @Query("SELECT SUM(amount_minor) FROM bills WHERE deleted = 0 AND bill_type = 'INCOME' AND date >= :monthStart AND date < :nextMonthStart")
    fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Long?>

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
        "SELECT SUM(CASE WHEN bill_type = 'EXPENSE' THEN -amount_minor ELSE amount_minor END) " +
            "FROM bills WHERE deleted = 0 AND account_id = :accountId"
    )
    suspend fun getAccountNet(accountId: Long): Long?

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


    @Query("SELECT * FROM bills WHERE deleted = 0 AND date >= :dayStart AND date < :dayEnd ORDER BY date DESC, COALESCE(sort_order, created_at) DESC, created_at DESC")
    suspend fun getBillsByDay(dayStart: Long, dayEnd: Long): List<Bill>

    @Query("SELECT category_name, SUM(amount_minor) as total FROM bills WHERE deleted = 0 AND bill_type = 'EXPENSE' AND date >= :dayStart AND date < :dayEnd GROUP BY category_name ORDER BY total DESC")
    suspend fun getDailyExpenseSummary(dayStart: Long, dayEnd: Long): List<DailyCategoryAmount>

    @Query("SELECT category_name, SUM(amount_minor) as total FROM bills WHERE deleted = 0 AND bill_type = 'INCOME' AND date >= :dayStart AND date < :dayEnd GROUP BY category_name ORDER BY total DESC")
    suspend fun getDailyIncomeSummary(dayStart: Long, dayEnd: Long): List<DailyCategoryAmount>

    @Query("SELECT COUNT(*) FROM bills WHERE deleted = 0 AND date >= :dayStart AND date < :dayEnd")
    suspend fun getDailyBillCount(dayStart: Long, dayEnd: Long): Int

    @Query("UPDATE bills SET server_id = :serverId, updated_at = :updatedAt, base_updated_at = :updatedAt, dirty = 0 WHERE id = :localId")

    suspend fun updateServerId(localId: Long, serverId: Long, updatedAt: Long)

    // ===== 省钱挑战 / 成就：日粒度统计 =====

    // 一天一行（GROUP BY date），驱动挑战进度与打卡墙的全部派生口径。
    // 400 天观察窗口局限：挑战页传「今天 - 400 天 ~ 明天」，窗口外的历史天数不参与统计——
    // 这是有意取舍：限定窗口可让 GROUP BY 走 bills(date) 索引做轻量响应式聚合；
    // 全时累计口径（累计记账天数、首笔账）另由下面的 countRecordedDays / getFirstBillDate 补齐。
    // 支出 = SUM(CASE WHEN 支出类型 THEN amount_minor ELSE 0 END)，整数分累加无浮点误差；
    // 只记收入的日子 billCount > 0 且 expenseMinor = 0，即「无消费日」。
    @Query(
        "SELECT date AS dayStart, COUNT(*) AS billCount, " +
            "SUM(CASE WHEN bill_type = 'EXPENSE' THEN amount_minor ELSE 0 END) AS expenseMinor " +
            "FROM bills WHERE deleted = 0 AND date >= :start AND date < :end " +
            "GROUP BY date ORDER BY date ASC"
    )
    fun observeDailySpendStats(start: Long, end: Long): Flow<List<DailySpendStat>>

    // 全时累计记账天数（COUNT DISTINCT date），不受观察窗口限制。
    @Query("SELECT COUNT(DISTINCT date) FROM bills WHERE deleted = 0")
    suspend fun countRecordedDays(): Int

    // 首笔账单日期（MIN(date)），还没有任何账单时为 NULL。
    @Query("SELECT MIN(date) FROM bills WHERE deleted = 0")
    suspend fun getFirstBillDate(): Long?
}
