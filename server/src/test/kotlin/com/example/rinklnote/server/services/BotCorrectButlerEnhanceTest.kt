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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 阶段 2 · BotCorrectService 管家增强（多笔撤回 + 属性指代删除）单测。
 * 重点回归约束：「撤销」单发仍走既有 DELETE_LAST 单笔链路，「改金额30」「改分类 交通」
 * 行为零变化（BotCorrectServiceTest 为既有回归基线，本类只覆盖新增模式与共存边界）。
 */
class BotCorrectButlerEnhanceTest {

    private val billService = BillService()

    @Before
    fun setup() {
        TestDatabase.connect("botbutler")
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
                it[UsersTable.phone] = "13800000043"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun bill(amountMinor: Long, remark: String? = null) = billService.createBill(
        userId = 1L, amountMinor = amountMinor, categoryName = "三餐", remark = remark
    )

    private fun handle(content: String): String? = BotCorrectService.handle(content, 1L, billService)

    private fun deletedFlag(billId: Long): Boolean = transaction {
        BillsTable.selectAll().where { BillsTable.id eq billId }.single()[BillsTable.deleted]
    }

    // ── 多笔撤回 ──

    @Test
    fun `撤销两笔软删两笔并逐行回执`() {
        val first = bill(1000L)
        val second = bill(2000L)
        val reply = handle("撤销两笔")!!
        // 每一笔一行小盘撤销回执（锚点由 MascotVoice 锁定，随机变体均含锚点）
        assertEquals(2, reply.split("已撤销最近一单").size - 1)
        assertTrue(reply.contains("共撤销 2 笔"))
        assertTrue(deletedFlag(first.id))
        assertTrue(deletedFlag(second.id))
    }

    @Test
    fun `多笔撤回中途没账单可撤`() {
        bill(1000L)
        val reply = handle("撤销3笔")!!
        assertTrue(reply.contains("已撤销最近一单"))
        assertTrue(reply.contains("没有更多账单可撤销"))
        assertTrue(reply.contains("共撤销 1 笔"))
        // 全部历史账单（含软删）之后没有可撤的了
        val reply2 = handle("撤销2笔")!!
        assertTrue(reply2.contains("没有更多账单可撤销"))
        assertTrue(reply2.contains("共撤销 0 笔"))
    }

    @Test
    fun `超过5笔给引导且不删账单`() {
        val target = bill(1000L)
        val reply = handle("撤销8笔")!!
        assertTrue(reply.contains("最多撤销 5 笔"))
        assertFalse(deletedFlag(target.id))
    }

    @Test
    fun `数字笔数与最近前面修饰均可识别`() {
        repeat(4) { bill((it + 1) * 100L) }
        val reply = handle("删除最近2笔")!!
        assertTrue(reply.contains("共撤销 2 笔"))
        val reply2 = handle("删掉前面2笔")!!
        assertTrue(reply2.contains("共撤销 2 笔"))
        assertEquals(0, billService.allBills(1L).size)
    }

    // ── 属性指代删除 ──

    @Test
    fun `删除刚才买奶茶的按备注命中`() {
        val target = bill(1500L, remark = "一点点奶茶")
        val reply = handle("删除刚才买奶茶的")!!
        // 锚点「已删除」由 MascotVoice.deleteReceipt 锁定（回执只含分类名+金额，不含备注）
        assertTrue(reply.contains("已删除"))
        assertTrue(reply.contains("已删除：三餐 ¥15.00"))
        assertTrue(deletedFlag(target.id))
    }

    @Test
    fun `删除刚才买奶茶的按分类名命中`() {
        // 分类「交通」不含关键词，改用备注缺失、分类命中的场景：关键词取「三餐」
        val target = bill(1500L)
        val reply = handle("删除最近吃三餐的那笔")!!
        assertTrue(reply.contains("已删除"))
        assertTrue(deletedFlag(target.id))
    }

    @Test
    fun `多笔命中删最近那笔并注明`() {
        val older = bill(1000L, remark = "奶茶")
        val newer = bill(2000L, remark = "奶茶第二杯")
        val reply = handle("删除刚才买奶茶的")!!
        assertTrue(reply.contains("已删除"))
        assertTrue(reply.contains("命中2笔"))
        // 只删最近那笔（createdAt 相同时按 id 倒序取最新）
        assertTrue(deletedFlag(newer.id))
        assertFalse(deletedFlag(older.id))
    }

    @Test
    fun `关键词无命中给引导`() {
        bill(2000L, remark = "午饭")
        val reply = handle("删除刚才买披萨的")!!
        assertTrue(reply.contains("没找到"))
        assertTrue(reply.contains("披萨"))
    }

    @Test
    fun `纯虚词或无关键词返回null落回旧链路`() {
        bill(2000L, remark = "奶茶")
        // 关键词是「那笔」→ 纯虚词
        assertNull(handle("删除刚才买的那笔"))
        // 刚才/最近后没有可提取的关键词
        assertNull(handle("删除刚才的"))
        // 含「删」「最近」但不是指代删除句式
        assertNull(handle("最近有点想删除记录"))
    }

    // ── 与既有指令的共存回归 ──

    @Test
    fun `撤销单发仍走既有单笔链路`() {
        val target = bill(2000L)
        val reply = handle("撤销")!!
        assertTrue(reply.contains("已撤销最近一单"))
        assertTrue(reply.contains("三餐 ¥20.00"))
        assertTrue(deletedFlag(target.id))
    }

    @Test
    fun `改金额与改分类不受增强影响`() {
        bill(2000L)
        assertTrue(handle("改金额30")!!.contains("已修改：三餐 ¥20.00 → ¥30.00"))
        // 金额已改为 30，改分类回执沿用当前金额
        assertTrue(handle("改分类 交通")!!.contains("→ 交通 ¥30.00"))
        // 删上一笔句式保持原样
        bill(1000L)
        assertTrue(handle("删上一笔")!!.contains("已撤销最近一单"))
    }
}
