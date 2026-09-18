package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.FuzzyAmount
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UserMemoryTable
import com.example.rinklnote.server.tables.UsersTable
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 1.3：NLU 理解升级单测 —— 模糊金额区间（取中值+区间标注）、上下文指代
 * （「跟上次一样/老样子」查记忆层，歧义追问）、常见品牌→类目映射。
 * LLM 用指向关闭端口的 dummy（永不命中），保证用例确定性。
 */
class NLUUpgradeTest {

    private val llmParser = LLMParser(
        LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500)
    )
    private val billService = BillService()
    private val nlu = DefaultNLUService(RuleBasedParser(), llmParser, billService)

    @Before
    fun setup() {
        TestDatabase.connect("nluupgrade")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable,
                VoiceKeywordsTable, UserMemoryTable
            )
            VoiceKeywordsTable.deleteAll()
            UserMemoryTable.deleteAll()
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
                it[UsersTable.phone] = "13800000051"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    // ── 模糊金额区间 ──

    @Test
    fun `模糊区间词表解析与中值`() {
        assertEquals(30.0 to 40.0, FuzzyAmount.parse("三四十"))
        assertEquals(40.0 to 50.0, FuzzyAmount.parse("打车四五十块"))
        assertEquals(20.0 to 29.0, FuzzyAmount.parse("二十几块"))
        assertEquals(20.0 to 90.0, FuzzyAmount.parse("几十块钱"))
        assertEquals(100.0 to 199.0, FuzzyAmount.parse("一百多块"))
        assertEquals(80.0 to 120.0, FuzzyAmount.parse("百来块"))
        assertEquals(900.0 to 1200.0, FuzzyAmount.parse("千把块"))
        assertEquals("中值", 35.0, FuzzyAmount.mid(30.0 to 40.0), 1e-9)
        // 精确中文数字不得误判为区间
        assertNull("四十三块=43 不是区间", FuzzyAmount.parse("四十三块"))
        assertNull("四十五块=45 不是区间", FuzzyAmount.parse("四十五块"))
        assertNull("普通文本", FuzzyAmount.parse("通勤月票"))
        // 标注文案
        assertTrue(FuzzyAmount.note(30.0 to 40.0).contains("区间30~40元"))
        assertTrue(FuzzyAmount.note(30.0 to 40.0).contains("中值35"))
    }

    @Test
    fun `区间金额取中值落账且VoiceResult带标注`() = runBlocking {
        // 「咖啡」不在词表 → LLM 兜底不命中 → 分类 null，但区间金额应出中值 35
        val r = nlu.parse("咖啡三四十块", 1L)
        assertEquals(35.0, r.amount ?: 0.0, 1e-9)
        assertNotNull(r.amountNote)
        assertTrue(r.amountNote!!.contains("区间30~40元"))
        assertTrue(r.amountNote!!.contains("中值35"))
    }

    @Test
    fun `精确中文数字金额不受区间逻辑影响`() = runBlocking {
        val r = nlu.parse("打车四十三块", 1L)
        assertEquals(43.0, r.amount ?: 0.0, 1e-9)
        assertNull(r.amountNote)
    }

    // ── 上下文指代（跟上次一样/老样子）──

    @Test
    fun `跟上次一样命中记忆层唯一商家`() = runBlocking {
        UserMemoryService.recordBill(1L, "瑞幸咖啡9.9元", "饮品")
        UserMemoryService.recordBill(1L, "瑞幸咖啡12元", "饮品")
        val r = nlu.parse("跟上次一样20元", 1L)
        assertEquals(20.0, r.amount ?: 0.0, 1e-9)
        assertEquals("饮品", r.categoryName)
        assertEquals("remark 记商家名（便于记忆层累计到同一家）", "瑞幸咖啡", r.remark)
        assertTrue(r.amountNote!!.contains("瑞幸咖啡"))
    }

    @Test
    fun `老样子不带金额时追问`() = runBlocking {
        UserMemoryService.recordBill(1L, "瑞幸咖啡9.9元", "饮品")
        val r = nlu.parse("老样子", 1L)
        assertNull(r.amount)
        assertNotNull(r.askReply)
        assertTrue(r.askReply!!.contains("瑞幸咖啡"))
        assertTrue(r.askReply!!.contains("多少钱"))
    }

    @Test
    fun `无记忆时引导先记账`() = runBlocking {
        val r = nlu.parse("跟上次一样", 1L)
        assertNotNull(r.askReply)
        assertTrue(r.askReply!!.contains("先正常记几笔"))
    }

    @Test
    fun `并列最高频时追问候选`() = runBlocking {
        // 用品牌映射之外的中性商家词（否则品牌关键词会在显式分类守卫处短路指代路径）
        UserMemoryService.recordBill(1L, "楼下奶茶店12元", "饮品")
        UserMemoryService.recordBill(1L, "老王包子铺8元", "三餐")
        val r = nlu.parse("跟上次一样", 1L)
        assertNotNull(r.askReply)
        assertTrue(r.askReply!!.contains("楼下奶茶"))
        assertTrue(r.askReply!!.contains("老王包子"))
        // 点名其中一家则不再追问
        val named = nlu.parse("跟上次一样 楼下奶茶15元", 1L)
        assertEquals("饮品", named.categoryName)
        assertEquals(15.0, named.amount ?: 0.0, 1e-9)
    }

    @Test
    fun `指代消息点名显式分类时让位给分类`() = runBlocking {
        UserMemoryService.recordBill(1L, "瑞幸咖啡9.9元", "饮品")
        // 「午饭」命中「饭」→ 三餐：显式分类优先于记忆层
        val r = nlu.parse("照旧记一笔午饭20元", 1L)
        assertEquals("三餐", r.categoryName)
        assertEquals(20.0, r.amount ?: 0.0, 1e-9)
    }

    // ── 品牌 → 类目映射 ──

    @Test
    fun `品牌归类映射`() {
        val parser = RuleBasedParser()
        assertEquals("三餐", parser.parse("瑞幸咖啡35", emptyList()))
        assertEquals("三餐", parser.parse("麦当劳套餐30元", emptyList()))
        assertEquals("三餐", parser.parse("美团外卖28", emptyList()))
        assertEquals("交通", parser.parse("滴滴回家22元", emptyList()))
        assertEquals("日用", parser.parse("山姆采购120元", emptyList()))
        assertEquals("网购", parser.parse("京东下单199", emptyList()))
        assertEquals("娱乐", parser.parse("B站大会员25元", emptyList()))
        // 既有关键词不受品牌追加影响（只增不改）
        assertEquals("三餐", parser.parse("午餐20元", emptyList()))
        assertEquals("网购", parser.parse("淘宝买了东西", emptyList()))
    }
}
