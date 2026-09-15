package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

/**
 * 省钱挑战表。scope 唯一键 = (user_id, type, period_start)，由 ChallengeService.upsert 保证
 * （命中即更新，不新增行）。
 */
object ChallengesTable : Table("challenges") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    // 挑战类型：NO_SPEND_DAY | BOOKKEEPING_STREAK | WEEKLY_BUDGET
    val type = varchar("type", 32)
    // 周期起点（epoch millis，Asia/Shanghai）：月挑战=当月1日0点；周挑战=周一0点；连续记账=承诺日0点。
    val periodStart = long("period_start")
    // 目标值。单位由 type 决定：NO_SPEND_DAY / BOOKKEEPING_STREAK = 天数；WEEKLY_BUDGET = 整数分（金额）。
    // 与 App 端约定一致，不单设 goalUnit 列。
    val goal = long("goal")
    // 挑战状态：ACTIVE | ACHIEVED | MISSED
    val status = varchar("status", 16).default("ACTIVE")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").nullable()
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}
