package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object AiApiTokensTable : Table("ai_api_tokens") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()   // SHA-256 hex, plaintext never stored
    val name = varchar("name", 60)
    val createdAt = long("created_at")
    val revokedAt = long("revoked_at").nullable().default(null)

    override val primaryKey = PrimaryKey(id)
}