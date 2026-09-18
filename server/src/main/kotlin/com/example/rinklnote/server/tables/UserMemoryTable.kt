package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

/**
 * 个人记忆层（2026-09-18 Task 1.2）：用户聚合画像 KV。
 *
 * key 形如 `top_merchants`（常去商家聚合）；value 是 JSON 数组（商家名+次数+各分类票数）。
 * **只存聚合，绝不含单笔明细与金额**（隐私红线）；value 全文由 UserMemoryService 维护，
 * 截断后（≤200 字）注入 LLM prompt。
 */
object UserMemoryTable : Table("user_memory") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val key = varchar("key", 64)
    val value = text("value")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_user_memory_user_key", userId, key)
    }
}
