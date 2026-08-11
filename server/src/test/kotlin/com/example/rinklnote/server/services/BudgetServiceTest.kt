package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BudgetServiceTest {

    private val service = BudgetService()

    @Before
    fun setup() {
        // Fresh in-memory DB per test so the shared JVM's Exposed default is never
        // pointed at a stale database left by another test class.
        TestDatabase.connect("budgettest")
        transaction {
            SchemaUtils.create(UsersTable, BudgetsTable)
            BudgetsTable.deleteAll()
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

    @Test
    fun `upsert creates a new budget for a user and month`() {
        val created = service.upsert(1L, monthStart = 100, amount = 500.0)
        assertTrue(created.id > 0)
        assertEquals(100, created.monthStart)
        assertEquals(500.0, created.amount, 0.0001)

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
}
