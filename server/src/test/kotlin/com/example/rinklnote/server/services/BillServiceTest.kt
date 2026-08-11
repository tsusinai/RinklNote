package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * BillService sync pagination + monthly aggregation (Phase 1 & 3 regressions).
 * Uses an in-memory H2 database seeded with the default categories/accounts.
 */
class BillServiceTest {

    private val service = BillService()
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Before
    fun setup() {
        // Fresh in-memory DB per test: identity sequences reset, no cross-class
        // transaction-manager leakage.
        TestDatabase.connect("billtest")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable
            )
            // Clear in FK dependency order so the shared in-memory DB is pristine per test.
            BillsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        service.seedIfNeeded()
        insertUser(1L, "13800000021")
        insertUser(2L, "13800000022")
    }

    private fun insertUser(id: Long, phone: String) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.phone] = phone
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun insertBill(
        userId: Long,
        amount: Double,
        updatedAt: Long,
        date: Long = updatedAt,
        billType: String = "EXPENSE",
        deleted: Boolean = false
    ): Long = transaction {
        BillsTable.insert {
            it[BillsTable.userId] = userId
            it[BillsTable.amount] = amount
            it[BillsTable.billType] = billType
            it[BillsTable.categoryId] = 1
            it[BillsTable.categoryName] = "三餐"
            it[BillsTable.accountId] = 1
            it[BillsTable.date] = date
            it[BillsTable.billSource] = "APP"
            it[BillsTable.createdAt] = updatedAt
            it[BillsTable.updatedAt] = updatedAt
            it[BillsTable.deleted] = deleted
        } get BillsTable.id
    }

    @Test
    fun `sync paginates with composite cursor covering an updatedAt tie`() {
        // Five bills; B and C share the same updatedAt so a naive (updatedAt-only)
        // cursor would duplicate or skip C.
        val a = insertBill(1L, 10.0, updatedAt = 1000)
        val b = insertBill(1L, 20.0, updatedAt = 2000)
        val c = insertBill(1L, 30.0, updatedAt = 2000) // tie with b
        val d = insertBill(1L, 40.0, updatedAt = 3000)
        val e = insertBill(1L, 50.0, updatedAt = 4000)

        val page1 = service.syncBills(1L, after = null, afterId = null, limit = 2)
        assertEquals(2, page1.bills.size)
        assertTrue(page1.hasMore)
        assertEquals(b, page1.nextAfterId)
        assertEquals(2000L, page1.nextAfter)

        val page2 = service.syncBills(1L, after = page1.nextAfter, afterId = page1.nextAfterId, limit = 2)
        assertEquals(2, page2.bills.size)
        assertTrue(page2.hasMore)
        assertEquals(listOf(c, d), page2.bills.map { it.id })
        assertEquals(3000L, page2.nextAfter)

        val page3 = service.syncBills(1L, after = page2.nextAfter, afterId = page2.nextAfterId, limit = 2)
        assertFalse(page3.hasMore)
        assertEquals(listOf(e), page3.bills.map { it.id })

        val allIds = page1.bills.map { it.id } + page2.bills.map { it.id } + page3.bills.map { it.id }
        assertEquals(listOf(a, b, c, d, e), allIds) // no duplicates, no gaps
    }

    @Test
    fun `sync returns tombstones for deleted bills but never other users bills`() {
        insertBill(1L, 10.0, updatedAt = 1000)
        insertBill(1L, 20.0, updatedAt = 2000, deleted = true)
        insertBill(2L, 30.0, updatedAt = 3000)

        val page = service.syncBills(1L, after = null, afterId = null, limit = 200)
        // Sync deliberately includes soft-deleted bills as tombstones so clients
        // can reconcile deletions; it must still never leak another user's bills.
        assertEquals(2, page.bills.size)
        assertEquals(listOf(10.0, 20.0), page.bills.map { it.amount })
        assertFalse(page.hasMore)
    }

    @Test
    fun `monthlyStats aggregates only non-deleted bills within the month`() {
        val monthStart = LocalDate.of(2026, 8, 1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val nextMonthStart = LocalDate.of(2026, 9, 1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        // Within August 2026
        insertBill(1L, 100.0, updatedAt = 100, date = monthStart + 1)
        insertBill(1L, 50.0, updatedAt = 200, date = monthStart + 2, billType = "INCOME")
        insertBill(1L, 25.0, updatedAt = 300, date = monthStart + 3, deleted = true)
        // Outside August
        insertBill(1L, 999.0, updatedAt = 400, date = nextMonthStart + 1)
        insertBill(2L, 777.0, updatedAt = 500, date = monthStart + 4)

        val stats = service.monthlyStats(1L, monthStart, nextMonthStart)
        assertEquals(100.0, stats.totalExpense, 0.0001)
        assertEquals(50.0, stats.totalIncome, 0.0001)
        assertEquals(listOf("三餐" to 100.0), stats.topExpenseCategories)
    }

    @Test
    fun `sync results are ordered by updatedAt then id`() {
        insertBill(1L, 10.0, updatedAt = 3000)
        insertBill(1L, 20.0, updatedAt = 1000)
        insertBill(1L, 30.0, updatedAt = 2000)

        val page = service.syncBills(1L, after = null, afterId = null, limit = 200)
        assertEquals(listOf(20.0, 30.0, 10.0), page.bills.map { it.amount })
        // ordering by (updatedAt, id) — the composite key used by clients
        transaction {
            val rows = BillsTable.selectAll()
                .where { BillsTable.userId eq 1L }
                .orderBy(BillsTable.updatedAt to SortOrder.ASC, BillsTable.id to SortOrder.ASC)
                .map { it[BillsTable.amount] }
            assertEquals(listOf(20.0, 30.0, 10.0), rows)
        }
    }
}
