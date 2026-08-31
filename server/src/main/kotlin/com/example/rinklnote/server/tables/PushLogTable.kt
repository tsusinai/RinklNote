package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object PushLogTable : Table("push_log") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val type = varchar("type", 20)      // MONTHLY_SUMMARY / ANOMALY / HABIT
    val dayKey = varchar("day_key", 20) // "yyyy-MM" 或 "yyyy-MM-dd"
    val pushedAt = long("pushed_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_push_log_user_type_day", userId, type, dayKey)
    }
}
