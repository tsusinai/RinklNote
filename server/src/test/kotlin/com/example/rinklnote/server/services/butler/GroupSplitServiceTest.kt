package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.AaRecordsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
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
 * 阶段 2 · 群聊 AA 分账（GroupSplitService）单测：
 * 拆分纯函数（均分 / 除不尽 / 非法参数）、建单解析与落库（AA 台账 + 自付账单）、
 * AA记录 / AA结清 指令、无人数与无金额的边界引导。
 */
class GroupSplitServiceTest {

    private val billService = BillService()
    private val service = GroupSplitService(billService)

    @Before
    fun setup() {
        TestDatabase.connect("groupsplit")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, AaRecordsTable)
            BillsTable.deleteAll()
            AaRecordsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000041"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun inc(text: String, groupOpenid: String? = null) = ButlerIncoming(
        userId = 1L,
        openid = "openid-1",
        chatType = if (groupOpenid != null) ButlerChatType.GROUP else ButlerChatType.PRIVATE,
        groupOpenid = groupOpenid,
        msgId = "m1",
        text = text,
        voiceUrl = null,
        imageUrl = null
    )

    private fun handle(text: String, groupOpenid: String? = null): String? =
        runBlocking { service.handle(inc(text, groupOpenid), text) }

    // ── 拆分纯函数 ──

    @Test
    fun `splitMinor 均分与除不尽余数给前几人`() {
        // 480元 = 48000分 / 3人 → 整除
        assertEquals(listOf(16000L, 16000L, 16000L), service.splitMinor(48000L, 3))
        // 100分 / 3人 → 余 1 分给前 1 人
        assertEquals(listOf(34L, 33L, 33L), service.splitMinor(100L, 3))
        // 1分 / 3人 → 发起人拿 1 分（保证自付账单金额恒 > 0）
        assertEquals(listOf(1L, 0L, 0L), service.splitMinor(1L, 3))
    }

    @Test
    fun `splitMinor 非法参数返回空列表`() {
        assertTrue(service.splitMinor(0L, 3).isEmpty())
        assertTrue(service.splitMinor(-100L, 3).isEmpty())
        assertTrue(service.splitMinor(100L, 0).isEmpty())
        assertTrue(service.splitMinor(100L, -1).isEmpty())
    }

    // ── 建单 ──

    @Test
    fun `AA 480 3人 建单落台账与自付账单`() = runBlocking {
        val reply = service.handle(inc("AA 480 3人"), "AA 480 3人")!!
        // 回执锁定：事由缺省「聚会」、总额、每人应付、自付确认
        assertTrue(reply.contains("🧾 小盘AA结算单"))
        assertTrue(reply.contains("事由：聚会"))
        assertTrue(reply.contains("总额：¥480.00（3人）"))
        assertTrue(reply.contains("每人应付：¥160.00"))
        assertTrue(reply.contains("你的那份 ¥160.00 已帮记上"))
        assertTrue(reply.contains("回「AA记录」查看历史 AA 单"))
        // 自付账单落库（48000 / 3 = 16000 分）
        val bills = billService.allBills(1L)
        assertEquals(1, bills.size)
        assertEquals(16000L, bills[0].amountMinor)
        assertEquals("QQ", bills[0].source)
        assertTrue(bills[0].remark!!.contains("AA·聚会（自付1/3）"))
        // AA 台账落库
        val rows = transaction { AaRecordsTable.selectAll().toList() }
        assertEquals(1, rows.size)
        assertEquals(48000L, rows[0][AaRecordsTable.totalMinor])
        assertEquals(3, rows[0][AaRecordsTable.headCount])
        assertFalse(rows[0][AaRecordsTable.settled])
        assertEquals("人1:16000;人2:16000;人3:16000", rows[0][AaRecordsTable.membersText])
    }

    @Test
    fun `事由词命中与分类解析`() = runBlocking {
        val reply = service.handle(inc("聚餐 AA 480 3人"), "聚餐 AA 480 3人")!!
        assertTrue(reply.contains("事由：聚餐"))
        // 「聚餐」含词表「餐」→ 三餐
        assertEquals("三餐", billService.allBills(1L).single().categoryName)
    }

    @Test
    fun `除不尽回执锁定为 前N人 其余 写法`() = runBlocking {
        // 1元 = 100分 / 3人 → [34,33,33] 分
        val reply = service.handle(inc("AA 1元 3人"), "AA 1元 3人")!!
        assertTrue(reply.contains("每人应付：前1人 ¥0.34，其余 ¥0.33"))
        assertTrue(reply.contains("你的那份 ¥0.34 已帮记上"))
        assertEquals(34L, billService.allBills(1L).single().amountMinor)
    }

    @Test
    fun `无人数给引导文案`() = runBlocking {
        assertEquals("几个人平摊呀？回「AA 480 3人」就行～", service.handle(inc("AA 480"), "AA 480"))
    }

    @Test
    fun `无金额或非AA意图返回null落回正常路由`() = runBlocking {
        // 无 AA 意图词
        assertNull(service.handle(inc("午餐20元"), "午餐20元"))
        // 有 AA 意图词但提取不出金额（「3人」是人数不是总额）
        assertNull(service.handle(inc("平摊快乐不谈钱"), "平摊快乐不谈钱"))
        assertNull(service.handle(inc("AA 3人"), "AA 3人"))
    }

    @Test
    fun `中文数字与元后缀人数据取`() = runBlocking {
        val reply = service.handle(inc("火锅均摊300元三个人"), "火锅均摊300元三个人")!!
        assertTrue(reply.contains("总额：¥300.00（3人）"))
        assertTrue(reply.contains("每人应付：¥100.00"))
    }

    // ── AA记录 / AA结清 ──

    @Test
    fun `AA记录 空清单给引导`() = runBlocking {
        val reply = service.handle(inc("AA记录"), "AA记录")!!
        assertTrue(reply.contains("还没有 AA 单"))
    }

    @Test
    fun `AA记录 输出编号清单并结清`() = runBlocking {
        service.handle(inc("AA 480 3人"), "AA 480 3人")
        val list = service.handle(inc("AA记录"), "AA记录")!!
        assertTrue(list.contains("#1 聚会 ¥480.00 · 3人 · 每人 ¥160.00"))
        assertFalse(list.contains("✅已结清"))
        // 结清后清单带 ✅ 标记
        val settle = service.handle(inc("AA结清 1"), "AA结清 1")!!
        assertEquals("✅ AA单「聚会」已结清", settle)
        assertTrue(service.handle(inc("AA记录"), "AA记录")!!.contains("✅已结清"))
        assertTrue(transaction { AaRecordsTable.selectAll().single()[AaRecordsTable.settled] })
    }

    @Test
    fun `AA结清 不存在的编号给引导`() = runBlocking {
        val reply = service.handle(inc("AA结清 9"), "AA结清 9")!!
        assertTrue(reply.contains("没找到编号 9"))
    }

    @Test
    fun `AA记录 群内优先本群账目`() = runBlocking {
        service.handle(inc("AA 1 2人"), "AA 1 2人") // 私聊发起，groupOpenid=null（裸数字按元，总额 ¥1.00）
        service.handle(inc("AA 4 2人", groupOpenid = "G1"), "AA 4 2人") // 群内发起（总额 ¥4.00）
        val inGroup = service.handle(inc("AA记录", groupOpenid = "G1"), "AA记录")!!
        assertTrue(inGroup.contains("¥4.00"))
        assertFalse(inGroup.contains("¥1.00"))
        // 私聊看全部
        val private = service.handle(inc("AA记录"), "AA记录")!!
        assertTrue(private.contains("¥1.00"))
        assertTrue(private.contains("¥4.00"))
    }

    // ── AA帮助 ──

    @Test
    fun `AA帮助 返回用法说明`() = runBlocking {
        val reply = service.handle(inc("AA帮助"), "AA帮助")!!
        assertTrue(reply.contains("AA 480 3人"))
        assertTrue(reply.contains("AA记录"))
    }
}
