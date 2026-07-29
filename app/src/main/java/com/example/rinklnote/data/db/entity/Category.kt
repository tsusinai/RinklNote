package com.example.rinklnote.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name", "bill_type"], unique = true)]
)
@androidx.compose.runtime.Immutable
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @androidx.room.ColumnInfo(name = "icon_name") val iconName: String,
    @androidx.room.ColumnInfo(name = "bill_type") val billType: String = "EXPENSE"
)
