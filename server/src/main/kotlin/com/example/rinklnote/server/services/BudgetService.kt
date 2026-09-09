package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

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

@Serializable
data class BudgetSummaryDTO(
    val periodStart: Long,
    val totalBudget: BudgetDTO? = null,
    val totalExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetDTO>,
    val subCategoryBudgets: List<SubCategoryBudgetDTO>,
    val lastMonthSurplus: Double? = null
)

@Serializable
data class CategoryBudgetDTO(
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,
    val expense: Double
)

@Serializable
data class SubCategoryBudgetDTO(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amount: Double,
    val expense: Double
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

    /**
     * Monthly summary: total/category/sub-category budgets plus expenses, and the
     * last month's surplus. [periodStart] is normalized to the first day of its
     * month in Asia/Shanghai.
     */
    fun summary(userId: Long, periodStart: Long): BudgetSummaryDTO = transaction {
        val zone = ZoneId.of("Asia/Shanghai")
        val startDate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(periodStart), zone)
            .toLocalDate()
            .withDayOfMonth(1)
        val monthStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextStart = startDate.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val prevStart = startDate.minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val expenseRows = BillsTable.select(
            BillsTable.amount, BillsTable.categoryId, BillsTable.subCategoryName
        ).where {
            (BillsTable.userId eq userId) and
                (BillsTable.billType eq "EXPENSE") and
                (BillsTable.deleted eq false) and
                (BillsTable.date greaterEq monthStart) and
                (BillsTable.date less nextStart)
        }.toList()
        val totalExpense = expenseRows.sumOf { it[BillsTable.amount] }
        val categoryExpense = expenseRows.groupBy { it[BillsTable.categoryId] }
            .mapValues { (_, rows) -> rows.sumOf { it[BillsTable.amount] } }
        val subCategoryExpense = expenseRows
            .filter { it[BillsTable.subCategoryName] != null }
            .groupBy { it[BillsTable.categoryId] to it[BillsTable.subCategoryName] }
            .mapValues { (_, rows) -> rows.sumOf { it[BillsTable.amount] } }

        val budgetRows = BudgetsTable.selectAll().where {
            (BudgetsTable.userId eq userId) and
                (BudgetsTable.monthStart eq monthStart) and
                (BudgetsTable.periodType eq "MONTHLY") and
                (BudgetsTable.deleted eq false)
        }.toList()

        val totalBudgetRow = budgetRows.firstOrNull {
            it[BudgetsTable.categoryId] == null && it[BudgetsTable.subCategoryId] == null
        }
        val categoryBudgets = budgetRows
            .filter { it[BudgetsTable.categoryId] != null && it[BudgetsTable.subCategoryId] == null }
            .map { row ->
                val categoryId = row[BudgetsTable.categoryId]!!
                val categoryName = CategoriesTable.select(CategoriesTable.name)
                    .where { CategoriesTable.id eq categoryId }
                    .firstOrNull()?.get(CategoriesTable.name) ?: ""
                CategoryBudgetDTO(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    amount = row[BudgetsTable.amount],
                    expense = categoryExpense[categoryId] ?: 0.0
                )
            }
        val subCategoryBudgets = budgetRows
            .filter { it[BudgetsTable.subCategoryId] != null }
            .map { row ->
                val subCategoryId = row[BudgetsTable.subCategoryId]!!
                val subRow = SubCategoriesTable.selectAll()
                    .where { SubCategoriesTable.id eq subCategoryId }
                    .firstOrNull()
                val name = subRow?.get(SubCategoriesTable.name) ?: ""
                val parentCategoryId = subRow?.get(SubCategoriesTable.parentCategoryId) ?: 0L
                SubCategoryBudgetDTO(
                    subCategoryId = subCategoryId,
                    name = name,
                    parentCategoryId = parentCategoryId,
                    amount = row[BudgetsTable.amount],
                    expense = subCategoryExpense[parentCategoryId to name] ?: 0.0
                )
            }

        val lastMonthSurplus = BudgetsTable.selectAll().where {
            (BudgetsTable.userId eq userId) and
                (BudgetsTable.monthStart eq prevStart) and
                (BudgetsTable.periodType eq "MONTHLY") and
                (BudgetsTable.deleted eq false) and
                (BudgetsTable.categoryId eq null) and
                (BudgetsTable.subCategoryId eq null)
        }.singleOrNull()?.let { prev ->
            val prevExpense = BillsTable.select(BillsTable.amount.sum())
                .where {
                    (BillsTable.userId eq userId) and
                        (BillsTable.billType eq "EXPENSE") and
                        (BillsTable.deleted eq false) and
                        (BillsTable.date greaterEq prevStart) and
                        (BillsTable.date less monthStart)
                }
                .first()[BillsTable.amount.sum()] ?: 0.0
            prev[BudgetsTable.amount] - prevExpense
        }

        BudgetSummaryDTO(
            periodStart = monthStart,
            totalBudget = totalBudgetRow?.toDTO(),
            totalExpense = totalExpense,
            categoryBudgets = categoryBudgets,
            subCategoryBudgets = subCategoryBudgets,
            lastMonthSurplus = lastMonthSurplus
        )
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
