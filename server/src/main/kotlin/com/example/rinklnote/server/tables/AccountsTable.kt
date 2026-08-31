package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object AccountsTable : Table("accounts") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id).nullable()
    val name = varchar("name", 50)
    val balance = double("balance").default(0.0)
    val iconColor = varchar("icon_color", 10)
    val updatedAt = long("updated_at").default(0)
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_accounts_user_name", userId, name)
    }
}
