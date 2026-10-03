package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.BillCapture
import kotlinx.coroutines.flow.Flow

@Dao
interface BillCaptureDao {

    @Insert
    suspend fun insert(capture: BillCapture): Long

    @Update
    suspend fun update(capture: BillCapture)

    @Query("SELECT * FROM bill_captures WHERE id = :id")
    suspend fun getById(id: Long): BillCapture?

    /** 待确认清单（确认模式的对账底座）。 */
    @Query("SELECT * FROM bill_captures WHERE state = 'PENDING' ORDER BY occurred_at DESC")
    fun observePending(): Flow<List<BillCapture>>

    /** 待确认笔数（首页角标实时响应）。 */
    @Query("SELECT COUNT(*) FROM bill_captures WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** 捕获记录页：最近 50 条（全状态，按发生时间倒序）。 */
    @Query("SELECT * FROM bill_captures ORDER BY occurred_at DESC LIMIT 50")
    fun observeRecent(): Flow<List<BillCapture>>

    /** 捕获级去重：同包名 + 同金额在窗口内且未被忽略的捕获，视为同一笔（通知重发/连环通知）。 */
    @Query(
        "SELECT * FROM bill_captures WHERE package_name = :packageName AND amount_minor = :amountMinor " +
            "AND occurred_at >= :from AND occurred_at <= :to AND state != 'IGNORED' LIMIT 1"
    )
    suspend fun findDuplicateCapture(packageName: String, amountMinor: Long, from: Long, to: Long): BillCapture?

    /** 渐进信任统计：累计自动入账笔数。 */
    @Query("SELECT COUNT(*) FROM bill_captures WHERE state = 'BOOKED'")
    suspend fun countBooked(): Int

    @Query("DELETE FROM bill_captures WHERE occurred_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
