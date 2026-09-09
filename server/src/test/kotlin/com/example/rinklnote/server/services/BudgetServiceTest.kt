package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.get
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BudgetServiceTest {

    private val service = BudgetService()
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Before
    fun setup() {
        // Fresh in-memory DB per test so the shared JVM's Exposed default is never
        // pointed at a stale database left by another test class.
        TestDatabase.connect("budgettest")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BudgetsTable, BillsTable
            )
            // Clear in FK dependency order so the shared in-memory DB is pristine per test.
            BillsTable.deleteAll()
            BudgetsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
            insertUser(1L, "13800000011")
            insertUser(2L, "13800000012")
        }
    }

    private fun insertUser(id: Long, phone: String) {
        UsersTable.insert {
            it[UsersTable.id] = id
            it[UsersTable.phone] = phone
            it[UsersTable.passwordHash] = "hash"
            it[UsersTable.createdAt] = "2026-01-01"
        }
    }

    private fun monthStart(month: String): Long =
        LocalDate.parse("$month-01").atStartOfDay(shanghai).toInstant().toEpochMilli()

    private fun insertCategory(name: String, billType: String = "EXPENSE"): Long = transaction {
        CategoriesTable.insert {
            it[CategoriesTable.name] = name
            it[CategoriesTable.iconName] = "icon"
            it[CategoriesTable.billType] = billType
        } get CategoriesTable.id
    }

    private fun insertSubCategory(name: String, parentCategoryId: Long): Long = transaction {
        SubCategoriesTable.insert {
            it[SubCategoriesTable.name] = name
            it[SubCategoriesTable.parentCategoryId] = parentCategoryId
        } get SubCategoriesTable.id
    }

    private fun insertAccount(userId: Long): Long = transaction {
        AccountsTable.insert {
            it[AccountsTable.userId] = userId
            it[AccountsTable.name] = "测试账户"
            it[AccountsTable.iconColor] = "#FFFFFF"
            it[AccountsTable.updatedAt] = System.currentTimeMillis()
        } get AccountsTable.id
    }

    private fun insertExpense(
        userId: Long,
        amount: Double,
        date: Long,
        categoryId: Long,
        accountId: Long,
        subCategoryName: String? = null
    ) {
        transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amount] = amount
                it[BillsTable.billType] = "EXPENSE"
                it[BillsTable.categoryId] = categoryId
                it[BillsTable.categoryName] = "三餐"
                it[BillsTable.accountId] = accountId
                it[BillsTable.subCategoryName] = subCategoryName
                it[BillsTable.date] = date
                it[BillsTable.billSource] = "APP"
                it[BillsTable.createdAt] = date
            }
        }
    }

    @Test
    fun `upsert creates a new budget for a user and month`() {
        val created = service.upsert(1L, monthStart = 100, amount = 500.0)
        assertTrue(created.id > 0)
        assertEquals(100, created.monthStart)
        assertEquals(500.0, created.amount, 0.0001)
        assertEquals("MONTHLY", created.periodType)
        assertNull(created.categoryId)
        assertNull(created.subCategoryId)

        val all = service.list(1L)
        assertEquals(1, all.size)
        assertEquals(created.id, all[0].id)
    }

    @Test
    fun `upsert with same user and month updates instead of duplicating`() {
        val first = service.upsert(1L, monthStart = 200, amount = 300.0)
        val second = service.upsert(1L, monthStart = 200, amount = 888.0)

        assertEquals(first.id, second.id)
        assertEquals(888.0, second.amount, 0.0001)
        assertEquals(1, service.list(1L).size)
    }

    @Test
    fun `upsert revives a deleted row for the same month`() {
        val created = service.upsert(1L, monthStart = 300, amount = 100.0)
        // Simulate a remote soft-delete of the budget row.
        transaction {
            BudgetsTable.update({ BudgetsTable.id eq created.id }) { it[BudgetsTable.deleted] = true }
        }

        val revived = service.upsert(1L, monthStart = 300, amount = 250.0)
        assertEquals(created.id, revived.id)
        assertTrue(!revived.deleted)
        assertEquals(250.0, revived.amount, 0.0001)
    }

    @Test
    fun `list is scoped per user`() {
        service.upsert(1L, monthStart = 400, amount = 100.0)
        service.upsert(2L, monthStart = 400, amount = 999.0)

        val user1 = service.list(1L)
        val user2 = service.list(2L)
        assertEquals(1, user1.size)
        assertEquals(1, user2.size)
        assertNotEquals(user1[0].amount, user2[0].amount)
    }

    @Test
    fun `upsert keeps total and category budgets in same month and list returns both`() {
        val total = service.upsert(1L, monthStart = 100, amount = 2000.0)
        val categoryId = insertCategory("交通")
        val category = service.upsert(1L, monthStart = 100, amount = 800.0, categoryId = categoryId)

        val all = service.list(1L)
        assertEquals(2, all.size)

        val totalDto = all.first { it.id == total.id }
        assertEquals(2000.0, totalDto.amount, 0.0001)
        assertNull(totalDto.categoryId)
        assertNull(totalDto.subCategoryId)
        assertEquals("MONTHLY", totalDto.periodType)

        val categoryDto = all.first { it.id == category.id }
        assertEquals(800.0, categoryDto.amount, 0.0001)
        assertEquals(categoryId, categoryDto.categoryId)
        assertNull(categoryDto.subCategoryId)
        assertEquals("MONTHLY", categoryDto.periodType)
    }

    @Test
    fun `upsert with same user month and category updates instead of duplicating`() {
        val categoryId = insertCategory("交通")
        val first = service.upsert(1L, monthStart = 200, amount = 300.0, categoryId = categoryId)
        val second = service.upsert(1L, monthStart = 200, amount = 999.0, categoryId = categoryId)

        assertEquals(first.id, second.id)
        assertEquals(999.0, second.amount, 0.0001)
        assertEquals(1, service.list(1L).size)
        assertEquals(categoryId, second.categoryId)
    }

    @Test
    fun `summary aggregates total category and sub category budgets with expenses`() {
        val start = monthStart("2026-09")
        val mealId = insertCategory("三餐")
        val subId = insertSubCategory("早餐", mealId)
        val accountId = insertAccount(1L)

        service.upsert(1L, monthStart = start, amount = 2000.0)
        service.upsert(1L, monthStart = start, amount = 500.0, categoryId = mealId)
        service.upsert(1L, monthStart = start, amount = 300.0, categoryId = mealId, subCategoryId = subId)

        insertExpense(1L, 60.0, date = start + 86_400_000, categoryId = mealId, accountId = accountId)
        insertExpense(
            1L, 40.0, date = start + 2 * 86_400_000,
            categoryId = mealId, accountId = accountId, subCategoryName = "早餐"
        )

        val s = service.summary(1L, start)

        assertEquals(start, s.periodStart)
        assertEquals(2000.0, s.totalBudget?.amount ?: 0.0, 0.0001)
        assertEquals(100.0, s.totalExpense, 0.0001)

        assertEquals(1, s.categoryBudgets.size)
        val category = s.categoryBudgets[0]
        assertEquals(mealId, category.categoryId)
        assertEquals("三餐", category.categoryName)
        assertEquals(500.0, category.amount, 0.0001)
        assertEquals(100.0, category.expense, 0.0001)

        assertEquals(1, s.subCategoryBudgets.size)
        val sub = s.subCategoryBudgets[0]
        assertEquals(subId, sub.subCategoryId)
        assertEquals("早餐", sub.name)
        assertEquals(mealId, sub.parentCategoryId)
        assertEquals(300.0, sub.amount, 0.0001)
        assertEquals(40.0, sub.expense, 0.0001)
    }

    @Test
    fun `summary returns last month surplus when previous budget exists`() {
        val start = monthStart("2026-09")
        val prevStart = monthStart("2026-08")
        val mealId = insertCategory("三餐")
        val accountId = insertAccount(1L)

        service.upsert(1L, monthStart = prevStart, amount = 1000.0)
        insertExpense(1L, 600.0, date = prevStart + 86_400_000, categoryId = mealId, accountId = accountId)

        val s = service.summary(1L, start)
        assertEquals(400.0, s.lastMonthSurplus ?: 0.0, 0.0001)
    }

    @Test
    fun `summary returns null last month surplus when previous month has no budget`() {
        val start = monthStart("2026-09")
        val s = service.summary(1L, start)
        assertNull(s.lastMonthSurplus)
    }
}
