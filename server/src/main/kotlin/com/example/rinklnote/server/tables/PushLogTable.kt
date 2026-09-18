package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object PushLogTable : Table("push_log") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val type = varchar("type", 20)      // MONTHLY_SUMMARY / ANOMALY / HABIT
    val dayKey = varchar("day_key", 20) // "yyyy-MM" 或 "yyyy-MM-dd"
    val pushedAt = long("pushed_at")
    // 2026-09-18 Task 0.7（通道健康度 + 失败重试）：
    // channel：本次推送选中的目标通道（QQ/FEISHU/WECOM/MP）；列后加，历史行可能为 null（健康度按 UNKNOWN 归组）。
    // status：OK=已送达；RETRYING=发送失败、内存重试队列挂起中；FAILED=重试耗尽彻底失败（重启丢队列后也可查）。
    val channel = varchar("channel", 20).nullable()
    val status = varchar("status", 20).default("OK")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_push_log_user_type_day", userId, type, dayKey)
    }
}
