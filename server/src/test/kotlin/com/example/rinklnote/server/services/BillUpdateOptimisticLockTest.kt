package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BillService.updateBill 乐观锁（2026-09-18 全端优化 Task 0.1）服务级用例：
 * 版本条件必须落在 UPDATE 的 WHERE 里（单条语句，无先读后写窗口）；
 * 0 行命中时二次区分「不存在 → NotFound / 版本不匹配 → VersionConflict」，
 * 冲突时并发写入方的数据必须原样保留。
 */
class BillUpdateOptimisticLockTest {

    companion object {
        private const val USER_ID = 1L
        private const val VERSION = 1_000L
    }

    @Before
    fun setUp() {
        TestDatabase.connect("billupdatelock")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, AccountsTable, BillsTable)
            UsersTable.insert {
                it[id] = USER_ID
                it[phone] = "13800000001"
                it[createdAt] = "2026-09-18"
            }
            CategoriesTable.insert {
                it[id] = 1L
                it[name] = "三餐"
                it[iconName] = "meals"
                it[billType] = "EXPENSE"
            }
            AccountsTable.insert {
                it[id] = 1L
                it[userId] = USER_ID
                it[name] = "无账户"
                it[iconColor] = "#F97D1D"
                it[updatedAt] = 0L
            }
            seedBill()
        }
    }

    private fun seedBill() {
        transaction {
            BillsTable.insert {
                it[id] = 1L
                it[userId] = USER_ID
                it[amount] = 10.0
                it[amountMinor] = 1000L
                it[billType] = "EXPENSE"
                it[categoryId] = 1L
                it[categoryName] = "三餐"
                it[accountId] = 1L
                it[remark] = "初始备注"
                it[date] = VERSION
                it[billSource] = "WEB"
                it[createdAt] = VERSION - 100
                it[updatedAt] = VERSION
            }
        }
    }

    private fun updateBill(baseUpdatedAt: Long?): BillUpdateResult =
        BillService().updateBill(
            id = 1L,
            userId = USER_ID,
            baseUpdatedAt = baseUpdatedAt,
            amountMinor = 2500L,
            billType = "EXPENSE",
            categoryId = 1L,
            categoryName = "三餐",
            subCategoryName = null,
            accountId = 1L,
            remark = "更新后备注",
            sortOrder = null,
            latitude = null,
            longitude = null
        )

    @Test
    fun `matching version updates and advances updatedAt`() {
        val result = updateBill(VERSION)
        assertTrue(result is BillUpdateResult.Updated)
        val dto = (result as BillUpdateResult.Updated).bill
        assertEquals(2500L, dto.amountMinor)
        assertEquals("更新后备注", dto.remark)
        assertTrue(dto.updatedAt!! > VERSION)

        val row = transaction { BillsTable.selectAll().where { BillsTable.id eq 1L }.single() }
        assertEquals(2500L, row[BillsTable.amountMinor])
        assertEquals("更新后备注", row[BillsTable.remark])
    }

    @Test
    fun `stale version conflicts and concurrent writer data is preserved`() {
        // 模拟并发写入方：先把版本与数据推进
        transaction {
            BillsTable.update({ BillsTable.id eq 1L }) {
                it[amountMinor] = 9900L
                it[amount] = 99.0
                it[remark] = "并发写入方"
                it[updatedAt] = VERSION + 5_000
            }
        }

        val result = updateBill(VERSION) // 携带过期版本
        assertTrue(result is BillUpdateResult.VersionConflict)
        val current = (result as BillUpdateResult.VersionConflict).current
        assertEquals(9900L, current.amountMinor)
        assertEquals("并发写入方", current.remark)
        assertEquals(VERSION + 5_000, current.updatedAt)

        // 并发写入方的数据原样保留，过期写入未生效
        val row = transaction { BillsTable.selectAll().where { BillsTable.id eq 1L }.single() }
        assertEquals(9900L, row[BillsTable.amountMinor])
        assertEquals("并发写入方", row[BillsTable.remark])
        assertEquals(VERSION + 5_000, row[BillsTable.updatedAt])
    }

    @Test
    fun `missing bill returns notFound`() {
        val result = BillService().updateBill(
            id = 999L, userId = USER_ID, baseUpdatedAt = 1L,
            amountMinor = 100L, billType = "EXPENSE", categoryId = 1L, categoryName = "三餐",
            subCategoryName = null, accountId = 1L, remark = null, sortOrder = null,
            latitude = null, longitude = null
        )
        assertTrue(result is BillUpdateResult.NotFound)
    }

    @Test
    fun `another users bill is not visible nor writable`() {
        val result = BillService().updateBill(
            id = 1L, userId = 777L, baseUpdatedAt = VERSION,
            amountMinor = 100L, billType = "EXPENSE", categoryId = 1L, categoryName = "三餐",
            subCategoryName = null, accountId = 1L, remark = null, sortOrder = null,
            latitude = null, longitude = null
        )
        assertTrue(result is BillUpdateResult.NotFound)
    }

    @Test
    fun `null baseUpdatedAt overwrites unconditionally`() {
        val result = updateBill(null)
        assertTrue(result is BillUpdateResult.Updated)
        assertEquals(2500L, (result as BillUpdateResult.Updated).bill.amountMinor)
    }
}
