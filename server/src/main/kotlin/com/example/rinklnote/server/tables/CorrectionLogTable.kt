package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object CorrectionLogTable : Table("correction_log") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val originalText = varchar("original_text", 500)
    val originalCategory = varchar("original_category", 50)
    val correctedCategory = varchar("corrected_category", 50)
    val processed = bool("processed").default(false)
    val correctedAt = varchar("corrected_at", 30)

    override val primaryKey = PrimaryKey(id)
}
