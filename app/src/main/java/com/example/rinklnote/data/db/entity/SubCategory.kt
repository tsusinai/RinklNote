package com.example.rinklnote.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sub_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["parent_category_id"]
        )
    ],
    indices = [Index("parent_category_id")]
)
@androidx.compose.runtime.Immutable
data class SubCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @androidx.room.ColumnInfo(name = "parent_category_id") val parentCategoryId: Long
)
