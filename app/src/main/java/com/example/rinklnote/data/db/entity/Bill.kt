package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@androidx.compose.runtime.Immutable
@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"]
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["account_id"]
        )
    ],
    indices = [
        Index("category_id"),
        Index("account_id"),
        Index("date"),
        Index("server_id", unique = true)
    ]
)
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    @ColumnInfo(name = "bill_type") val billType: String, // "EXPENSE" or "INCOME"
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "sub_category_name") val subCategoryName: String? = null,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val remark: String? = null,
    val date: Long, // timestamp of start of day
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    val source: String = "APP", // "APP" or "QQ"
    @ColumnInfo(name = "server_id") val serverId: Long? = null, // server bill id for dedup
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "base_updated_at") val baseUpdatedAt: Long? = null, // 最后被服务端确认的 updatedAt（条件 PUT 的 base）
    val deleted: Boolean = false,
    val dirty: Boolean = false // true = local edit/delete not yet pushed to server
)
