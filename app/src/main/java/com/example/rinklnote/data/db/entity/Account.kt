package com.example.rinklnote.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["server_id"], unique = true)]
)
@androidx.compose.runtime.Immutable
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balance: Double = 0.0,
    @androidx.room.ColumnInfo(name = "icon_color") val iconColor: String,
    @androidx.room.ColumnInfo(name = "server_id") val serverId: Long? = null,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false
)
