package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.Account

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: Account): Long

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAll(): List<Account>

    @Update
    suspend fun update(account: Account)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}
