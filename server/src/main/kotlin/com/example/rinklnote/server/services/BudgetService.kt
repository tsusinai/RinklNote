package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    val amountMinor: Long,
    // 旧字段，仅供旧客户端，勿用；值 = Money.fromMinor(amountMinor)。
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
    // 新字段（分，权威值）；旧客户端只发 amount 时回退。
    val amountMinor: Long? = null,
    // 旧字段（元），仅供回退。
    val amount: Double? = null,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BudgetSummaryDTO(
    val periodStart: Long,
    val totalBudget: BudgetDTO? = null,
    val totalExpenseMinor: Long = 0L,
    // 旧字段，仅供旧客户端。@EncodeDefault 必需：否则取值为 0.0（等于默认值）时会被
    // Json(encodeDefaults=false) 整个省略，旧客户端反序列化失败。
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val totalExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetDTO>,
    val subCategoryBudgets: List<SubCategoryBudgetDTO>,
    val lastMonthSurplusMinor: Long? = null,
    // 旧字段，仅供旧客户端；同上，必须强制输出。
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val lastMonthSurplus: Double? = null
)

@Serializable
data class CategoryBudgetDTO(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long,
    val expenseMinor: Long,
    // 旧字段，仅供旧客户端。
    val amount: Double,
    val expense: Double
)

@Serializable
data class SubCategoryBudgetDTO(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amountMinor: Long,
    val expenseMinor: Long,
    // 旧字段，仅供旧客户端。
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
        amountMinor: Long,
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
                    it[BudgetsTable.amountMinor] = amountMinor
                    it[BudgetsTable.amount] = Money.fromMinor(amountMinor)
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
                    it[BudgetsTable.amountMinor] = amountMinor
                    it[BudgetsTable.amount] = Money.fromMinor(amountMinor)
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
            BillsTable.amountMinor, BillsTable.categoryId, BillsTable.subCategoryName
        ).where {
            (BillsTable.userId eq userId) and
                (BillsTable.billType eq "EXPENSE") and
                (BillsTable.deleted eq false) and
                (BillsTable.date greaterEq monthStart) and
                (BillsTable.date less nextStart)
        }.toList()
        val totalExpense = expenseRows.sumOf { it[BillsTable.amountMinor] ?: 0L }
        val categoryExpense = expenseRows.groupBy { it[BillsTable.categoryId] }
            .mapValues { (_, rows) -> rows.sumOf { it[BillsTable.amountMinor] ?: 0L } }
        val subCategoryExpense = expenseRows
            .filter { it[BillsTable.subCategoryName] != null }
            .groupBy { it[BillsTable.categoryId] to it[BillsTable.subCategoryName] }
            .mapValues { (_, rows) -> rows.sumOf { it[BillsTable.amountMinor] ?: 0L } }

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
                val amount = row[BudgetsTable.amountMinor] ?: 0L
                val expense = categoryExpense[categoryId] ?: 0L
                CategoryBudgetDTO(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    amountMinor = amount,
                    expenseMinor = expense,
                    amount = Money.fromMinor(amount),
                    expense = Money.fromMinor(expense)
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
                val amount = row[BudgetsTable.amountMinor] ?: 0L
                val expense = subCategoryExpense[parentCategoryId to name] ?: 0L
                SubCategoryBudgetDTO(
                    subCategoryId = subCategoryId,
                    name = name,
                    parentCategoryId = parentCategoryId,
                    amountMinor = amount,
                    expenseMinor = expense,
                    amount = Money.fromMinor(amount),
                    expense = Money.fromMinor(expense)
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
            val prevExpense = BillsTable.select(BillsTable.amountMinor.sum())
                .where {
                    (BillsTable.userId eq userId) and
                        (BillsTable.billType eq "EXPENSE") and
                        (BillsTable.deleted eq false) and
                        (BillsTable.date greaterEq prevStart) and
                        (BillsTable.date less monthStart)
                }
                .first()[BillsTable.amountMinor.sum()] ?: 0L
            (prev[BudgetsTable.amountMinor] ?: 0L) - prevExpense
        }

        BudgetSummaryDTO(
            periodStart = monthStart,
            totalBudget = totalBudgetRow?.toDTO(),
            totalExpenseMinor = totalExpense,
            totalExpense = Money.fromMinor(totalExpense),
            categoryBudgets = categoryBudgets,
            subCategoryBudgets = subCategoryBudgets,
            lastMonthSurplusMinor = lastMonthSurplus,
            lastMonthSurplus = lastMonthSurplus?.let { Money.fromMinor(it) }
        )
    }

    private fun ResultRow.toDTO() = BudgetDTO(
        id = this[BudgetsTable.id],
        monthStart = this[BudgetsTable.monthStart],
        amountMinor = this[BudgetsTable.amountMinor] ?: 0L,
        amount = Money.fromMinor(this[BudgetsTable.amountMinor] ?: 0L),
        periodType = this[BudgetsTable.periodType],
        categoryId = this[BudgetsTable.categoryId],
        subCategoryId = this[BudgetsTable.subCategoryId],
        createdAt = this[BudgetsTable.createdAt],
        updatedAt = this[BudgetsTable.updatedAt],
        deleted = this[BudgetsTable.deleted]
    )
}
