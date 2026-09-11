package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo

data class DailyCategoryAmount(
    @ColumnInfo(name = "category_name")
    val categoryName: String,
    @ColumnInfo(name = "total")
    val total: Long
)
