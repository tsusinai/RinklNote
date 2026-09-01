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

    override val primaryKey = PrimaryKey(id)
}
