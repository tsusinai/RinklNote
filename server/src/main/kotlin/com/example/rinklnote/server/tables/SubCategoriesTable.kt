package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object SubCategoriesTable : Table("sub_categories") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 50)
    val parentCategoryId = long("parent_category_id").references(CategoriesTable.id).index()

    override val primaryKey = PrimaryKey(id)
}
