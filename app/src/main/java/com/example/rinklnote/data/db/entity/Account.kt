package com.example.rinklnote.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["name"], unique = true)]
)
@androidx.compose.runtime.Immutable
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balance: Double = 0.0,
    @androidx.room.ColumnInfo(name = "icon_color") val iconColor: String
)
