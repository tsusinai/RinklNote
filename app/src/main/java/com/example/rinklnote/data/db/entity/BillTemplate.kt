package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@androidx.compose.runtime.Immutable
@Entity(
    tableName = "bill_templates",
    indices = [Index("server_id", unique = true)]
)
data class BillTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    val label: String,
    // 金额单位：分（minor unit）。
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "sub_category_name") val subCategoryName: String? = null,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)
