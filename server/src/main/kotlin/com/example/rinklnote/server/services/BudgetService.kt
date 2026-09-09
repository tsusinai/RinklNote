package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BudgetsTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class BudgetDTO(
    val id: Long,
    val monthStart: Long,
    val amount: Double,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class UpsertBudgetRequest(
    val monthStart: Long,
    val amount: Double,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null
)

class BudgetService {

    fun list(userId: Long): List<BudgetDTO> = transaction {
        BudgetsTable.selectAll()
            .where { BudgetsTable.userId eq userId }
            .map { it.toDTO() }
    }

    /** Create-or-update for a (user, month, category, sub-category) scope. Returns the current row. */
    fun upsert(
        userId: Long,
        monthStart: Long,
        amount: Double,
        categoryId: Long? = null,
        subCategoryId: Long? = null,
        periodType: String = "MONTHLY"
    ): BudgetDTO {
        val now = System.currentTimeMillis()
        return transaction {
            val existing = BudgetsTable.selectAll()
                .where {
                    (BudgetsTable.userId eq userId) and
                        (BudgetsTable.monthStart eq monthStart) and
                        (BudgetsTable.categoryId eq categoryId) and
                        (BudgetsTable.subCategoryId eq subCategoryId)
                }
                .singleOrNull()

            if (existing != null) {
                val rowId = existing[BudgetsTable.id]
                BudgetsTable.update({ BudgetsTable.id eq rowId }) {
                    it[BudgetsTable.amount] = amount
                    it[BudgetsTable.periodType] = periodType
                    it[BudgetsTable.deleted] = false
                    it[BudgetsTable.updatedAt] = now
                }
                rowId
            } else {
                BudgetsTable.insert {
                    it[BudgetsTable.userId] = userId
                    it[BudgetsTable.monthStart] = monthStart
                    it[BudgetsTable.periodType] = periodType
                    it[BudgetsTable.categoryId] = categoryId
                    it[BudgetsTable.subCategoryId] = subCategoryId
                    it[BudgetsTable.amount] = amount
                    it[BudgetsTable.createdAt] = now
                    it[BudgetsTable.updatedAt] = now
                } get BudgetsTable.id
            }
        }.let { id ->
            transaction {
                BudgetsTable.selectAll().where { BudgetsTable.id eq id }.single().toDTO()
            }
        }
    }

    private fun ResultRow.toDTO() = BudgetDTO(
        id = this[BudgetsTable.id],
        monthStart = this[BudgetsTable.monthStart],
        amount = this[BudgetsTable.amount],
        periodType = this[BudgetsTable.periodType],
        categoryId = this[BudgetsTable.categoryId],
        subCategoryId = this[BudgetsTable.subCategoryId],
        createdAt = this[BudgetsTable.createdAt],
        updatedAt = this[BudgetsTable.updatedAt],
        deleted = this[BudgetsTable.deleted]
    )
}
