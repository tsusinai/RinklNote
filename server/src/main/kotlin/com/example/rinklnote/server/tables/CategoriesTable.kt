package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object CategoriesTable : Table("categories") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 50)
    val iconName = varchar("icon_name", 50)
    val billType = varchar("bill_type", 10) // "EXPENSE" or "INCOME"

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_category_name_type", name, billType)
    }
}
