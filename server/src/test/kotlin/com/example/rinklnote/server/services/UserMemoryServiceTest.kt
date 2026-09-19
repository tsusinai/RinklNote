package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.UserMemoryTable
import com.example.rinklnote.server.services.insight.InsightService
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 1.2：个人记忆层单测 —— 商家词提取、聚合累计、摘要生成、prompt 注入自查。
 * 隐私红线：记忆与注入内容只含「商家名+次数+首选分类」聚合，无单笔明细/金额/备注原文。
 */
class UserMemoryServiceTest {

    @Before
    fun setup() {
        TestDatabase.connect("usermemory")
        transaction {
            SchemaUtils.create(UserMemoryTable)
            UserMemoryTable.deleteAll()
        }
    }

    // ── 商家词提取 ──

    @Test
    fun `商家词提取去金额与日期`() {
        assertEquals("瑞幸咖啡", UserMemoryService.extractMerchantToken("瑞幸咖啡9.9元一杯"))
        assertEquals("瑞幸咖啡", UserMemoryService.extractMerchantToken("瑞幸咖啡 9.9"))
        assertEquals("瑞幸咖啡", UserMemoryService.extractMerchantToken("瑞幸咖啡15元拿铁"))
        assertEquals("午餐", UserMemoryService.extractMerchantToken("午餐20元"))
        assertEquals("超市购物", UserMemoryService.extractMerchantToken("9/18 超市购物 35.5元"))
        assertNull(UserMemoryService.extractMerchantToken(null))
        assertNull(UserMemoryService.extractMerchantToken(""))
        assertNull("纯金额提取不出商家词", UserMemoryService.extractMerchantToken("20元"))
        assertNull("单字不足2字", UserMemoryService.extractMerchantToken("买 5元"))
    }

    // ── 聚合累计 ──

    @Test
    fun `累计次数与首选分类取多数票`() {
        // 瑞幸：饮品 3 次、三餐 1 次 → 次数 4，首选分类 饮品（不同叫法聚到同一商家词）
        UserMemoryService.recordBill(1L, "瑞幸咖啡9.9元", "饮品")
        UserMemoryService.recordBill(1L, "瑞幸咖啡12元", "饮品")
        UserMemoryService.recordBill(1L, "瑞幸咖啡15元拿铁", "饮品")
        UserMemoryService.recordBill(1L, "瑞幸咖啡买三明治", "三餐")
        val stats = UserMemoryService.topMerchants(1L)
        assertEquals(1, stats.size)
        assertEquals("瑞幸咖啡", stats[0].name)
        assertEquals(4, stats[0].count)
        assertEquals("饮品", stats[0].topCategory)
    }

    @Test
    fun `多条商家按次数降序且KV截断上限`() {
        repeat(3) { UserMemoryService.recordBill(1L, "美团外卖", "三餐") }
        repeat(2) { UserMemoryService.recordBill(1L, "滴滴出行", "交通") }
        UserMemoryService.recordBill(1L, "山姆会员店", "日用")
        val stats = UserMemoryService.topMerchants(1L)
        assertEquals("多条商家按次数降序（山姆会员店截 4 字为山姆会员）",
            listOf("美团外卖", "滴滴出行", "山姆会员"), stats.map { it.name })
        // 截断上限：超过 MAX_MERCHANTS 条时只保留次数最多的前 20 条
        for (i in 1..UserMemoryService.MAX_MERCHANTS + 5) {
            UserMemoryService.recordBill(1L, "商家$i 号店", "日用")
        }
        assertTrue(UserMemoryService.topMerchants(1L).size <= UserMemoryService.MAX_MERCHANTS)
    }

    @Test
    fun `指代语境的整句备注不产生垃圾商家词`() {
        // 「跟上次一样/老样子」命中显式分类（打车/地铁）走规则记账时，落库备注是整句原文。
        // 指代词必须先剥掉，否则「跟上次一」「老样子」这类垃圾词会被当商家累计进画像，
        // 甚至成为 top 商家被 resolveReference 的「跟哪家一样」追问引用。
        UserMemoryService.recordBill(1L, "跟上次一样打车20元", "交通")
        UserMemoryService.recordBill(1L, "老样子，地铁上班6块", "交通")
        val names = UserMemoryService.topMerchants(1L).map { it.name }
        assertFalse(
            "指代词前缀不得成为商家词: $names",
            names.any { it.startsWith("跟上次") || it.startsWith("老样子") }
        )
        // 剥掉指代词/金额/标点后取前 4 字：「打车」「地铁上班」
        assertEquals(listOf("地铁上班", "打车"), names)
    }

    @Test
    fun `无分类时归入其他`() {
        UserMemoryService.recordBill(1L, "某某商家", null)
        assertEquals("其他", UserMemoryService.topMerchants(1L)[0].topCategory)
    }

    // ── 摘要生成与注入自查 ──

    @Test
    fun `摘要限200字且只含聚合不含明细金额`() {
        UserMemoryService.recordBill(1L, "瑞幸咖啡9.9元", "饮品")
        UserMemoryService.recordBill(1L, "瑞幸咖啡12元", "饮品")
        UserMemoryService.recordBill(1L, "美团外卖35元", "三餐")
        val summary = UserMemoryService.memorySummary(1L)
        assertTrue(summary.isNotEmpty())
        assertTrue("摘要限长 ≤200 字", summary.length <= UserMemoryService.SUMMARY_MAX_LEN)
        assertTrue(summary.contains("瑞幸咖啡"))
        assertTrue(summary.contains("饮品"))
        // 隐私自查：注入内容无金额符号、无备注原文里的金额数字
        assertFalse("摘要不得含金额符号", summary.contains("¥"))
        assertFalse("摘要不得含备注里的金额数字", summary.contains("9.9元"))
        assertFalse("摘要不得含金额数字 35", summary.contains("35"))
        // 空画像 → 空摘要
        assertEquals("", UserMemoryService.memorySummary(99L))
        assertEquals("", UserMemoryService.buildSummary(emptyList()))
    }

    @Test
    fun `naturalQueryContext注入记忆段且无记忆时省略`() {
        val withMemory = InsightService.naturalQueryContext(
            query = "这个月花了多少",
            memorySummary = "用户常去商家（聚合，无明细无金额）: 瑞幸(饮品2次)",
            categories = listOf("三餐", "交通"),
            year = 2026, month = 9, totalExpense = 10000L, totalIncome = 0L,
            topCategories = listOf("三餐" to 6000L), recentBills = emptyList()
        )
        assertTrue(withMemory.contains("用户记忆（聚合画像，无任何单笔明细/金额）"))
        assertTrue(withMemory.contains("瑞幸"))
        val withoutMemory = InsightService.naturalQueryContext(
            query = "这个月花了多少", categories = listOf("三餐"), year = 2026, month = 9,
            totalExpense = 10000L, totalIncome = 0L, topCategories = emptyList(), recentBills = emptyList()
        )
        assertFalse(withoutMemory.contains("用户记忆"))
    }
}
