package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val phone = varchar("phone", 20).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255).nullable()
    val qqNumber = varchar("qq_number", 20).nullable().uniqueIndex()
    val qqOpenid = varchar("qq_openid", 64).nullable().uniqueIndex()
    val createdAt = varchar("created_at", 30)
    val aiDisabled = bool("ai_disabled").default(false)

    // 日报推送（QQ 端）：子开关 + 发送时刻。ai_disabled 仍是全部主动推送的总闸。
    val dailyReportEnabled = bool("daily_report_enabled").default(false)
    val dailyReportHour = integer("daily_report_hour").default(9)
    val dailyReportMinute = integer("daily_report_minute").default(0)

    override val primaryKey = PrimaryKey(id)
}
