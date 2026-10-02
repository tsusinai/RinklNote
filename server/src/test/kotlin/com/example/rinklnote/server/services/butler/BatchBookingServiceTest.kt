package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.VoiceResult
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 阶段 2 · 批量/多行记账（BatchBookingService）单测：
 * 多行提取纯函数（跳行 / 命令行 / 无金额行 / 10 行上限）、待确认状态机
 * （确认落库 / 取消 / 去掉N / TTL 过期）、与正常单笔记账路由的边界。
 */
class BatchBookingServiceTest {

    private val billService = BillService()
    private lateinit var service: BatchBookingService

    /** 本服务全规则实现不依赖 NLU，测试里给一个永不命中的空实现。 */
    private val noopNlu = object : NLUService {
        override suspend fun parse(text: String, userId: Long): VoiceResult =
            VoiceResult(amount = null, categoryName = null, remark = "")
    }

    private var now = 1_000_000L

    @Before
    fun setup() {
        TestDatabase.connect("batchbook")
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
                it[UsersTable.phone] = "13800000042"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
        now = 1_000_000L
        service = BatchBookingService(billService, noopNlu) { now }
    }

    private fun inc(text: String) = ButlerIncoming(
        userId = 1L,
        openid = "openid-1",
        chatType = ButlerChatType.PRIVATE,
        groupOpenid = null,
        msgId = "m1",
        text = text,
        voiceUrl = null,
        imageUrl = null
    )

    private fun handle(text: String): String? = runBlocking { service.handle(inc(text), text) }

    // ── 多行提取纯函数 ──

    @Test
    fun `extractCandidates 多行提取并跳过无金额行`() {
        val items = service.extractCandidates("咖啡 18\n打车 25 元\n今天天气不错")
        assertEquals(2, items.size)
        // 「咖啡 18」：无元后缀取行内最后一个数字；规则词表没命中 → 分类 null
        assertEquals(1800L, items[0].amountMinor)
        assertNull(items[0].categoryName)
        // 「打车 25 元」：元后缀优先；「打车」→ 交通
        assertEquals(2500L, items[1].amountMinor)
        assertEquals("交通", items[1].categoryName)
    }

    @Test
    fun `extractCandidates 命令行与零金额行跳过`() {
        val items = service.extractCandidates("咖啡 18\n帮助\n0元 矿泉水\n取消\n打车 25 元")
        assertEquals(2, items.size)
        assertEquals(1800L, items[0].amountMinor)
        assertEquals(2500L, items[1].amountMinor)
    }

    @Test
    fun `extractCandidates 最多取前10行`() {
        val text = (1..12).joinToString("\n") { "午餐 $it 元" }
        val items = service.extractCandidates(text)
        assertEquals(10, items.size)
        assertEquals(100L, items.first().amountMinor)
        assertEquals(1000L, items.last().amountMinor)
    }

    // ── 与正常路由的边界 ──

    @Test
    fun `单行文本返回null不抢正常记账`() = runBlocking {
        assertNull(service.handle(inc("咖啡 18"), "咖啡 18"))
        // 含换行但只有 1 笔候选 → 也不接管
        assertNull(service.handle(inc("咖啡 18\n今天天气不错"), "咖啡 18\n今天天气不错"))
    }

    @Test
    fun `无待确认态时确认返回null`() = runBlocking {
        assertNull(service.handle(inc("确认"), "确认"))
        assertEquals(0, billService.allBills(1L).size)
    }

    // ── 待确认状态机 ──

    @Test
    fun `多行识别回编号清单`() = runBlocking {
        val reply = service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")!!
        assertTrue(reply.contains("识别到 2 笔待入账："))
        assertTrue(reply.contains("1. 其他 ¥18.00"))
        assertTrue(reply.contains("2. 交通 ¥25.00"))
        assertTrue(reply.contains("回复「确认」全部入账"))
        // 尚未落库
        assertEquals(0, billService.allBills(1L).size)
    }

    @Test
    fun `确认后逐笔落库并汇总`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")
        val reply = service.handle(inc("确认"), "确认")!!
        assertTrue(reply.contains("✅ 交通 ¥25.00"))
        assertTrue(reply.contains("共 2 笔 ¥43.00"))
        val bills = billService.allBills(1L)
        assertEquals(2, bills.size)
        assertEquals(setOf(1800L, 2500L), bills.map { it.amountMinor }.toSet())
        // 备注保留原始行文本
        assertTrue(bills.all { it.source == "QQ" })
        assertTrue(bills.any { it.remark == "咖啡 18" })
        assertTrue(bills.any { it.remark == "打车 25 元" })
    }

    @Test
    fun `去掉2后清单更新且确认只记剩余`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元\n午餐 30元"), "咖啡 18\n打车 25 元\n午餐 30元")
        val reply = service.handle(inc("去掉2"), "去掉2")!!
        assertTrue(reply.contains("已剔除第 2 笔，还剩 2 笔："))
        assertTrue(reply.contains("1. 其他 ¥18.00"))
        assertTrue(reply.contains("2. 三餐 ¥30.00"))
        val confirmReply = service.handle(inc("确认"), "确认")!!
        assertTrue(confirmReply.contains("共 2 笔 ¥48.00"))
        assertEquals(2, billService.allBills(1L).size)
    }

    @Test
    fun `去掉越界序号不破坏清单`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")
        val reply = service.handle(inc("去掉9"), "去掉9")!!
        assertTrue(reply.contains("清单里没有第 9 笔"))
        // 清单仍在，仍可确认
        assertTrue(service.handle(inc("确认"), "确认")!!.contains("共 2 笔"))
    }

    @Test
    fun `取消清态且不再响应确认`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")
        assertEquals("好嘞，这批先不入账～", service.handle(inc("取消"), "取消"))
        assertEquals(0, billService.allBills(1L).size)
        assertNull(service.handle(inc("确认"), "确认"))
    }

    @Test
    fun `剔空清单自动取消`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")
        val reply = service.handle(inc("去掉1"), "去掉1")!!
        assertTrue(reply.contains("已剔除第 1 笔，还剩 1 笔："))
        val reply2 = service.handle(inc("去掉1"), "去掉1")!!
        assertEquals("好嘞，都剔完了，这批先不入账～", reply2)
        assertNull(service.handle(inc("确认"), "确认"))
    }

    @Test
    fun `TTL过期后待确认态失效`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")
        now += ButlerSession.TTL_MILLIS + 1
        // 过期后「确认」不再命中（落回正常路由返回 null），账单不落库
        assertNull(service.handle(inc("确认"), "确认"))
        assertEquals(0, billService.allBills(1L).size)
        // 过期后可发起新批量
        now += 1
        val reply = service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")!!
        assertTrue(reply.contains("识别到 2 笔待入账："))
    }

    @Test
    fun `待确认态下其它文本不挡新记账`() = runBlocking {
        service.handle(inc("咖啡 18\n打车 25 元"), "咖啡 18\n打车 25 元")
        // 单行新记账：旧 pending 被丢弃，返回 null 落回正常路由
        assertNull(service.handle(inc("午餐20元"), "午餐20元"))
        // 旧 pending 已清，再「确认」无效果
        assertNull(service.handle(inc("确认"), "确认"))
        assertEquals(0, billService.allBills(1L).size)
        // 待确认态下粘贴新批量 → 覆盖旧态
        val reply = service.handle(inc("电影 40\n日用 66"), "电影 40\n日用 66")!!
        assertTrue(reply.contains("识别到 2 笔待入账："))
        assertTrue(service.handle(inc("确认"), "确认")!!.contains("共 2 笔 ¥106.00"))
    }
}
