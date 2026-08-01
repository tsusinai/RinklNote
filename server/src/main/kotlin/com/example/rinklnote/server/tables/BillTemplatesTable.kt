package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object BillTemplatesTable : Table("bill_templates") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val label = varchar("label", 30)
    val amount = double("amount")
    val categoryId = long("category_id").references(CategoriesTable.id)
    val categoryName = varchar("category_name", 50)
    val subCategoryName = varchar("sub_category_name", 50).nullable()
    val accountId = long("account_id").references(AccountsTable.id)
    val sortOrder = integer("sort_order").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
