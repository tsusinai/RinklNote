package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 挑战类型常量：TEXT 存储，镜像 domain/BillType 的常量写法（实体字段为纯 String，无需 TypeConverter）。 */
object ChallengeType {
    /** 无消费日挑战：goal = 目标天数。 */
    const val NO_SPEND_DAY = "NO_SPEND_DAY"

    /** 连续记账挑战：goal = 目标天数。 */
    const val BOOKKEEPING_STREAK = "BOOKKEEPING_STREAK"

    /** 周预算挑战：goal = 周支出上限（整数分）。 */
    const val WEEKLY_BUDGET = "WEEKLY_BUDGET"
}

/** 挑战状态常量：进行中 / 已达成 / 已错过（周期结束仍未达标）。 */
object ChallengeStatus {
    const val ACTIVE = "ACTIVE"
    const val ACHIEVED = "ACHIEVED"
    const val MISSED = "MISSED"
}

@androidx.compose.runtime.Immutable
@Entity(
    tableName = "challenges",
    indices = [Index("server_id", unique = true)]
)
data class Challenge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    // 挑战类型（ChallengeType 常量）。
    @ColumnInfo(name = "type") val type: String,
    // 周期起点：月挑战 = 当月 1 日 0 点；周挑战 = 周一 0 点；连续记账 = 承诺日 0 点（Asia/Shanghai）。
    @ColumnInfo(name = "period_start") val periodStart: Long,
    // 目标值，单位由 type 决定（有意不加 goalUnit 列）：
    //   NO_SPEND_DAY / BOOKKEEPING_STREAK → 天数；WEEKLY_BUDGET → 整数分（金额一律整数分，禁止 Double）。
    @ColumnInfo(name = "goal") val goal: Long,
    // 状态（ChallengeStatus 常量），新建行默认进行中。
    @ColumnInfo(name = "status", defaultValue = "ACTIVE") val status: String = ChallengeStatus.ACTIVE,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false // true = 本地新增/编辑/删除尚未推送到服务端
)
