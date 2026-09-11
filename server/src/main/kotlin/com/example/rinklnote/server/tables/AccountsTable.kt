package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object AccountsTable : Table("accounts") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id).nullable()
    val name = varchar("name", 50)
    // 旧浮点列，过渡期保留，勿直接读取。
    val balance = double("balance").default(0.0)
    // 整数分（权威值）。
    val balanceMinor = long("balance_minor").nullable()
    val iconColor = varchar("icon_color", 10)
    val updatedAt = long("updated_at").default(0)
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("uq_accounts_user_name", userId, name)
    }
}
