package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.Source

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
        // 复合索引（性能任务 2.8 索引复核）：所有查询都以 deleted = 0 打头、多数再按 date
        // 范围/排序（observeAll / observeByMonth / 日粒度统计），(deleted, date) 让列表与
        // 统计查询走索引序扫描，免去全表过滤 + 排序。
        Index("deleted", "date"),
        Index("server_id", unique = true)
    ]
)
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // 金额单位：分（minor unit），整数存储避免浮点漂移。展示统一走 Money.format。
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "bill_type") val billType: BillType = BillType.EXPENSE,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "sub_category_name") val subCategoryName: String? = null,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val remark: String? = null,
    val date: Long, // timestamp of start of day
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    val source: Source = Source.APP,
    @ColumnInfo(name = "server_id") val serverId: Long? = null, // server bill id for dedup
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "base_updated_at") val baseUpdatedAt: Long? = null, // 最后被服务端确认的 updatedAt（条件 PUT 的 base）
    // 同日内显式排序名次（拖动重排写入）；NULL = 未显式排序，查询按 COALESCE(sort_order, created_at) 兜底。
    @ColumnInfo(name = "sort_order") val sortOrder: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false, // true = local edit/delete not yet pushed to server
    // 经纬度（度）：仅用户在记/编账单时主动点「位置」打点的账单才有值；
    // null = 未打点（老账单、语音/QQ/AI 来源恒为 null），账单地图只展示有值的账单。
    val latitude: Double? = null,
    val longitude: Double? = null
)
