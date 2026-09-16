package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val phone = varchar("phone", 20).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255).nullable()
    val qqNumber = varchar("qq_number", 20).nullable().uniqueIndex()
    val qqOpenid = varchar("qq_openid", 64).nullable().uniqueIndex()

    // 多通道 bot 身份列（飞书 / 订阅号 / 企业微信）：可空 + 唯一索引，与 qq_openid 同构。
    // 一个用户可同时绑定多个通道；既有库由 createMissingTablesAndColumns 自动加列，无需手写迁移。
    val feishuOpenId = varchar("feishu_open_id", 64).nullable().uniqueIndex()
    val wechatOpenid = varchar("wechat_openid", 64).nullable().uniqueIndex()
    val wecomUserid = varchar("wecom_userid", 64).nullable().uniqueIndex()

    val createdAt = varchar("created_at", 30)

    // ── 个人资料（2026-09-17 个人资料页）──
    // 均可空：未设置时客户端自行回落（昵称回落手机号掩码、头像回落首字徽章）。
    // createMissingTablesAndColumns 会给既有库自动补列，无需手写迁移。
    val nickname = varchar("nickname", 32).nullable()
    val signature = varchar("signature", 120).nullable()
    // 生日存 ISO 日期串（yyyy-MM-dd），星座由客户端按公历区间实时推算，服务端不存。
    val birthday = varchar("birthday", 10).nullable()
    // 头像文件存 uploads/avatars/{id}.jpg（相对服务端工作目录），此处只存相对 URL（不含 ?v= 版本参数）。
    val avatarUrl = varchar("avatar_url", 255).nullable()
    // 徽章展示：逗号分隔的成就徽章 key（如 "record-30,budget-first"），上限 3 枚，格式由路由层校验。
    val showcaseBadges = varchar("showcase_badges", 255).nullable()

    val aiDisabled = bool("ai_disabled").default(false)

    // 日报推送（QQ 端）：子开关 + 发送时刻。ai_disabled 仍是全部主动推送的总闸。
    val dailyReportEnabled = bool("daily_report_enabled").default(false)
    val dailyReportHour = integer("daily_report_hour").default(9)
    val dailyReportMinute = integer("daily_report_minute").default(0)

    override val primaryKey = PrimaryKey(id)
}
