package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object BudgetsTable : Table("budgets") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val monthStart = long("month_start") // epoch millis of first day of month
    val periodType = varchar("period_type", 10).default("MONTHLY") // MONTHLY | YEARLY(二期)
    val categoryId = long("category_id").references(CategoriesTable.id).nullable()
    val subCategoryId = long("sub_category_id").references(SubCategoriesTable.id).nullable()
    // 旧浮点列，过渡期保留，勿直接读取。
    val amount = double("amount")
    // 整数分（权威值）。
    val amountMinor = long("amount_minor").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").nullable()
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}
