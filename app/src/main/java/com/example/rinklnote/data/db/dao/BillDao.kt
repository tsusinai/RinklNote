package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.rinklnote.data.db.entity.Bill
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Insert
    suspend fun insert(bill: Bill): Long

    @Query("SELECT * FROM bills ORDER BY date DESC, created_at DESC")
    fun observeAll(): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE date >= :monthStart AND date < :nextMonthStart ORDER BY date DESC")
    fun observeByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>>

    @Query("SELECT SUM(amount) FROM bills WHERE bill_type = 'EXPENSE' AND date >= :monthStart AND date < :nextMonthStart")
    suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double?

    @Query("SELECT SUM(amount) FROM bills WHERE bill_type = 'INCOME' AND date >= :monthStart AND date < :nextMonthStart")
    suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double?

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(bills: List<Bill>)
}
