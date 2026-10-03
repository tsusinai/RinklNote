package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 支付捕获台账（2026-10-03 自动记账方案 C）：NotificationListener 捕到的支付通知
 * 经解析/去重后的落痕，是「全自动 + 事后对账」的审计与确认底座。
 *
 * 生命周期：PENDING（确认模式待处理）→ BOOKED（已入账，billId 指向账单）/
 * IGNORED（用户忽略）；与已有账单命中去重 → MERGED（billId 指向被并入的账单）。
 * 只在端内存储，绝不参与同步（bills 的 source=AUTO 已随账单同步）。
 */
@Entity(
    tableName = "bill_captures",
    indices = [Index("state"), Index("occurred_at")]
)
data class BillCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 来源 App 包名（白名单内才捕获）。 */
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "notif_title") val notifTitle: String? = null,
    @ColumnInfo(name = "notif_text") val notifText: String? = null,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long? = null,
    /** "EXPENSE" / "INCOME"（与 BillType.value 对齐）。 */
    @ColumnInfo(name = "bill_type") val billType: String = "EXPENSE",
    val merchant: String? = null,
    /** 支付发生时刻（通知 postTime，epoch 毫秒）。 */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    /** PENDING / BOOKED / MERGED / IGNORED（常量见 [BillCaptureStates]）。 */
    val state: String,
    /** BOOKED / MERGED 时指向关联账单 id。 */
    @ColumnInfo(name = "bill_id") val billId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L
)

/** 捕获状态词表（避免魔法字符串散落）。 */
object BillCaptureStates {
    const val PENDING = "PENDING"
    const val BOOKED = "BOOKED"
    const val MERGED = "MERGED"
    const val IGNORED = "IGNORED"
}
