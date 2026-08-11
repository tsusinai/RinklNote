package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object BudgetsTable : Table("budgets") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val monthStart = long("month_start") // epoch millis of first day of month
    val amount = double("amount")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").nullable()
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}
