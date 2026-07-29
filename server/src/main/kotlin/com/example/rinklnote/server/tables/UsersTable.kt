package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val phone = varchar("phone", 20).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val qqNumber = varchar("qq_number", 20).nullable().uniqueIndex()
    val createdAt = varchar("created_at", 30)

    override val primaryKey = PrimaryKey(id)
}
