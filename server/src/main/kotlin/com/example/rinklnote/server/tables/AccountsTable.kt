package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object AccountsTable : Table("accounts") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 50).uniqueIndex()
    val balance = double("balance").default(0.0)
    val iconColor = varchar("icon_color", 10)

    override val primaryKey = PrimaryKey(id)
}
