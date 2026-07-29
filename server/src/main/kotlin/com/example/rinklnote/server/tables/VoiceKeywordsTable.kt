package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object VoiceKeywordsTable : Table("voice_keywords") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val keyword = varchar("keyword", 50)
    val categoryName = varchar("category_name", 50)
    val priority = integer("priority").default(10)
    val createdAt = varchar("created_at", 30)

    override val primaryKey = PrimaryKey(id)
}
