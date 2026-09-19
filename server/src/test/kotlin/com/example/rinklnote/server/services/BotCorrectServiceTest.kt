package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 1.1：Bot 多轮修正（改金额 / 改分类 / 删上一笔 / 撤销）单测。
 * 覆盖：指令解析、无账单引导、成功改/删、软删后不可再改再删。
 */
class BotCorrectServiceTest {

    private val billService = BillService()

    @Before
    fun setup() {
        TestDatabase.connect("botcorrect")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable)
            BillsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000031"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun bill(amountMinor: Long, categoryName: String = "三餐") = billService.createBill(
        userId = 1L, amountMinor = amountMinor, categoryName = categoryName, remark = null
    )

    private fun deletedFlag(billId: Long): Boolean = transaction {
        BillsTable.selectAll().where { BillsTable.id eq billId }.single()[BillsTable.deleted]
    }

    // ── 指令解析 ──

    @Test
    fun `指令解析命中各种句式`() {
        assertTrue(BotCommands.isLastBillCorrection("改金额30"))
        assertTrue(BotCommands.isLastBillCorrection("改金额改成 25.5元"))
        assertTrue(BotCommands.isLastBillCorrection("金额改成30"))
        assertTrue(BotCommands.isLastBillCorrection("金额改为12"))
        assertTrue(BotCommands.isLastBillCorrection("改分类 交通"))
        assertTrue(BotCommands.isLastBillCorrection("改分类改成交通"))
        assertTrue(BotCommands.isLastBillCorrection("改分类为交通费"))
        assertTrue(BotCommands.isLastBillCorrection("删上一笔"))
        assertTrue(BotCommands.isLastBillCorrection("删除上一笔"))
        assertTrue(BotCommands.isLastBillCorrection("撤销"))
        // 记账/问账消息不误伤
        assertFalse(BotCommands.isLastBillCorrection("午餐20元"))
        assertFalse(BotCommands.isLastBillCorrection("今天花了多少"))
        assertFalse(BotCommands.isLastBillCorrection("最近几笔"))
        // 正则取值
        assertEquals("30", BotCommands.EDIT_AMOUNT.find("改金额30")!!.groupValues[1])
        assertEquals("25.5", BotCommands.EDIT_AMOUNT.find("金额改成25.5元")!!.groupValues[1])
        assertEquals("交通", BotCommands.EDIT_CATEGORY.find("改分类 交通")!!.groupValues[1])
        assertEquals("交通费", BotCommands.EDIT_CATEGORY.find("改分类为交通费")!!.groupValues[1])
    }

    // ── 无账单 ──

    @Test
    fun `无账单时给引导文案`() {
        assertTrue(BotCorrectService.handle("撤销", 1L, billService)!!.contains("先记一笔"))
        assertTrue(BotCorrectService.handle("改金额30", 1L, billService)!!.contains("先记一笔"))
        assertTrue(BotCorrectService.handle("改分类 交通", 1L, billService)!!.contains("先记一笔"))
    }

    @Test
    fun `非修正指令返回null继续原路由`() {
        assertNull(BotCorrectService.handle("午餐20元", 1L, billService))
        assertNull(BotCorrectService.handle("这个月花了多少", 1L, billService))
    }

    // ── 改金额 ──

    @Test
    fun `改金额成功且回执含改前改后`() {
        val b = bill(2000L) // ¥20.00
        val reply = BotCorrectService.handle("改金额30", 1L, billService)!!
        assertTrue(reply.contains("三餐 ¥20.00"))
        assertTrue(reply.contains("→ ¥30.00"))
        val after = billService.getBill(b.id, 1L)!!
        assertEquals(3000L, after.amountMinor)
        // 金额兼容字段同步刷新
        assertEquals(30.0, after.amount, 1e-9)
    }

    @Test
    fun `改金额非法时提示格式`() {
        bill(2000L)
        val reply = BotCorrectService.handle("改金额", 1L, billService)!!
        // 「改金额」后面没有数字 → 金额解析失败
        assertTrue(reply.contains("没看懂"))
    }

    @Test
    fun `改金额过小或超大时提示格式而非静默异常`() {
        // 0.001 元 → 换整数分为 0 分（require(amountMinor>0) 会抛 IAE）；
        // 超长数字 → toMinor 的 longValueExact 溢出 ArithmeticException。
        // 两者都必须回到「没看懂」引导文案，绝不静默吞掉不回执。
        bill(2000L)
        assertTrue(BotCorrectService.handle("改金额0.001", 1L, billService)!!.contains("没看懂"))
        assertTrue(BotCorrectService.handle("改金额9999999999999999999", 1L, billService)!!.contains("没看懂"))
    }

    // ── 改分类 ──

    @Test
    fun `改分类成功且回执含改前改后`() {
        val b = bill(2000L, "三餐")
        val reply = BotCorrectService.handle("改分类 交通", 1L, billService)!!
        assertTrue(reply.contains("三餐 ¥20.00"))
        assertTrue(reply.contains("→ 交通 ¥20.00"))
        val after = billService.getBill(b.id, 1L)!!
        assertEquals("交通", after.categoryName)
    }

    @Test
    fun `改分类到收入分类时账单类型跟随`() {
        val b = bill(2000L, "三餐")
        BotCorrectService.handle("改分类 工资", 1L, billService)
        val after = billService.getBill(b.id, 1L)!!
        assertEquals("工资", after.categoryName)
        assertEquals("INCOME", after.billType)
    }

    @Test
    fun `改分类找不到时提示可用分类`() {
        bill(2000L)
        val reply = BotCorrectService.handle("改分类 不存在的分类", 1L, billService)!!
        assertTrue(reply.contains("没有找到"))
    }

    // ── 删除 / 撤销 ──

    @Test
    fun `撤销软删最近一单`() {
        val b = bill(2000L)
        val reply = BotCorrectService.handle("撤销", 1L, billService)!!
        assertTrue(reply.contains("已撤销最近一单"))
        assertTrue(reply.contains("三餐 ¥20.00"))
        assertTrue(deletedFlag(b.id))
    }

    // ── 软删后不可再改再删 ──

    @Test
    fun `软删后不可再删改且最近一单落到再前一笔`() {
        val first = bill(1000L)
        val second = bill(2000L)
        // 撤销最近一单（second）
        BotCorrectService.handle("撤销", 1L, billService)
        assertTrue(deletedFlag(second.id))
        // 对已软删账单的定向修改/删除全部无效
        assertNull(billService.updateBillAmount(second.id, 1L, 9999L))
        assertNull(billService.updateBillCategory(second.id, 1L, 1L, "三餐"))
        assertFalse(billService.deleteBill(second.id, 1L))
        assertEquals(2000L, billService.getBill(second.id, 1L)!!.amountMinor) // 数据未被动过
        // 「最近一单」自动落到再前一笔：再撤销一次删掉 first
        val reply = BotCorrectService.handle("撤销", 1L, billService)!!
        assertTrue(reply.contains("三餐 ¥10.00"))
        assertTrue(deletedFlag(first.id))
        // 全删光后回到「无账单」引导
        assertTrue(BotCorrectService.handle("撤销", 1L, billService)!!.contains("先记一笔"))
        assertNotNull(billService.getBill(first.id, 1L)) // 软删行仍可查（getBill 不过滤 deleted）
    }
}
