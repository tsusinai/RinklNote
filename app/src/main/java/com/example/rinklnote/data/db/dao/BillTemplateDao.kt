package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.rinklnote.data.db.entity.BillTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface BillTemplateDao {
    @Query("SELECT * FROM bill_templates ORDER BY sort_order")
    fun observeAll(): Flow<List<BillTemplate>>

    @Upsert
    suspend fun upsertAll(templates: List<BillTemplate>)

    @Query("DELETE FROM bill_templates")
    suspend fun deleteAll()

    @Query("DELETE FROM bill_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
