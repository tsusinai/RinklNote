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
    // 余额单位：分（minor unit）。
    @androidx.room.ColumnInfo(name = "balance_minor") val balanceMinor: Long = 0L,
    @androidx.room.ColumnInfo(name = "icon_color") val iconColor: String,
    @androidx.room.ColumnInfo(name = "icon_key") val iconKey: String = "WALLET",
    @androidx.room.ColumnInfo(name = "server_id") val serverId: Long? = null,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false
)

/**
 * 「无账户」兜底账户名：记账时未显式选择账户，金额记入该桶（不再强选账户）。
 * 名称保留：用户不能新建/重命名出第二个「无账户」，桶在资产页也不允许重命名/删除。
 * 桶随普通账户参与余额联动与总资产求和，是未分配资金的公共归属地。
 */
const val ACCOUNT_BUCKET_NAME = "无账户"

/** 是否为「无账户」兜底账户桶。真实钱包 = !isBucket()。 */
fun Account.isBucket(): Boolean = name == ACCOUNT_BUCKET_NAME

/** 供选择器/界面用：仅真实钱包账户（排除「无账户」桶）。 */
fun Account.isWallet(): Boolean = !isBucket()
