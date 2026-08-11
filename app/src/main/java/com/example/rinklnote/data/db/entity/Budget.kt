package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@androidx.compose.runtime.Immutable
@Entity(
    tableName = "budgets",
    indices = [Index("server_id", unique = true)]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "month_start") val monthStart: Long, // epoch of first day of month
    val amount: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false
)
