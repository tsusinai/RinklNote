package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 每用户账户模型：默认账户播种、增删改、按用户隔离。
 */
class AccountServiceTest {
    private val service = BillService()

    @Before
    fun setup() {
        TestDatabase.connect("accounttest")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable)
            BillsTable.deleteAll(); SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll(); CategoriesTable.deleteAll(); UsersTable.deleteAll()
        }
        insertUser(1L, "13800000021")
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

    @Test
    fun `ensureDefaultAccounts seeds the 3 defaults once and is idempotent`() {
        service.ensureDefaultAccounts(1L)
        assertEquals(listOf("微信", "支付宝", "默认"), service.accountsFor(1L).map { it.name })
        service.ensureDefaultAccounts(1L)
        assertEquals(listOf("微信", "支付宝", "默认"), service.accountsFor(1L).map { it.name })
    }

    @Test
    fun `createAccount adds a custom account only for that user`() {
        val w = service.createAccount(1L, "招商银行", "#123456", 0.0)
        assertTrue(w.id > 0)
        // 自定义账户与默认账户共存（无顺序要求）
        assertEquals(setOf("微信", "支付宝", "默认", "招商银行"), service.accountsFor(1L).map { it.name }.toSet())
    }

    @Test
    fun `renameAccount changes name and returns dto, null for other user or missing`() {
        val w = service.createAccount(1L, "招商银行", "#123456", 0.0)
        val renamed = service.renameAccount(w.id, 1L, "招行卡", "#000000")
        assertEquals("招行卡", renamed!!.name)
        assertNull(service.renameAccount(w.id, 99999L, "x", "y"))
        assertNull(service.renameAccount(999999, 1L, "x", "y"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createAccount rejects a duplicate name`() {
        service.ensureDefaultAccounts(1L)
        service.createAccount(1L, "微信", "#000000", 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renameAccount rejects renaming to an existing sibling`() {
        service.ensureDefaultAccounts(1L)
        val custom = service.createAccount(1L, "招商银行", "#123456", 0.0)
        val wechat = service.accountsFor(1L).first { it.name == "微信" }
        service.renameAccount(wechat.id, 1L, "招商银行", "#000000")
    }

    @Test
    fun `deleteAccount soft-deletes and hides it from accountsFor`() {
        val w = service.createAccount(1L, "招商银行", "#123456", 0.0)
        assertTrue(service.deleteAccount(w.id, 1L))
        assertFalse(service.accountsFor(1L).any { it.id == w.id })
    }

    @Test
    fun `accounts are isolated per user`() {
        insertUser(2L, "13800000022")
        service.ensureDefaultAccounts(1L)
        service.ensureDefaultAccounts(2L)
        service.createAccount(1L, "招商银行", "#123456", 0.0)
        assertEquals(4, service.accountsFor(1L).size)
        assertEquals(3, service.accountsFor(2L).size) // 用户2没有自定义账户
    }
}
