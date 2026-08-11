package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object BillsTable : Table("bills") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val amount = double("amount")
    val billType = varchar("bill_type", 10) // "EXPENSE" or "INCOME"
    val categoryId = long("category_id").references(CategoriesTable.id)
    val categoryName = varchar("category_name", 50)
    val subCategoryName = varchar("sub_category_name", 50).nullable()
    val accountId = long("account_id").references(AccountsTable.id)
    val remark = varchar("remark", 500).nullable()
    val date = long("date") // epoch millis
    val billSource = varchar("source", 10) // "QQ" or "APP"
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").nullable()
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}
