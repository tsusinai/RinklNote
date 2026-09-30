package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.services.SuggestionConfidence
import com.example.rinklnote.server.services.SuggestionSource
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NLU parse tests. The real LLM is never reached because every test text either
 * matches the rule-based parser or falls through to a dummy LLM pointed at a
 * closed port (returns null fast).
 */
class DefaultNLUServiceTest {

    // Dummy LLM: baseUrl on port 1 is refused instantly, timeout kept short.
    private val llmParser = LLMParser(
        LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500)
    )
    private val billService = BillService()
    private val nlu = DefaultNLUService(RuleBasedParser(), llmParser, billService)

    @Before
    fun setup() {
        TestDatabase.connect("nlutest")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable,
                VoiceKeywordsTable, UserMemoryTable
            )
            // Clear in FK dependency order so the shared in-memory DB is pristine per test.
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
                it[UsersTable.phone] = "13800000031"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    @Test
    fun `rule-based parse extracts amount and category`() = runBlocking {
        val r = nlu.parse("午餐15元", 1L)
        assertEquals(15.0, r.amount ?: 0.0, 0.0001)
        assertEquals("三餐", r.categoryName)
        assertEquals("午餐15元", r.remark)
    }

    @Test
    fun `amount extraction prefers explicit yuan suffix over earlier digits`() = runBlocking {
        // "8" and "1" (date digits) must not win over "20元".
        val r = nlu.parse("8月1日打车20元", 1L)
        assertEquals(20.0, r.amount ?: 0.0, 0.0001)
        assertEquals("交通", r.categoryName)
    }

    @Test
    fun `user custom keyword takes precedence over system keywords`() = runBlocking {
        transaction {
            VoiceKeywordsTable.insert {
                it[VoiceKeywordsTable.userId] = 1L
                it[VoiceKeywordsTable.keyword] = "星巴克"
                it[VoiceKeywordsTable.categoryName] = "日用"
                it[VoiceKeywordsTable.priority] = 10
                it[VoiceKeywordsTable.createdAt] = "2026-01-01"
            }
        }
        val r = nlu.parse("星巴克35元", 1L)
        assertEquals(35.0, r.amount ?: 0.0, 0.0001)
        assertEquals("日用", r.categoryName)
    }

    @Test
    fun `text without a number yields null amount`() = runBlocking {
        val r = nlu.parse("买了个普通东西", 1L)
        assertNull(r.amount)
        // No category matches, LLM fallback returns null on the closed port.
        assertNull(r.categoryName)
    }

    @Test
    fun `extractAmount parses uppercase chinese numerals`() = runBlocking {
        val r = nlu.parse("打车贰拾五元", 1L)
        assertEquals(25.0, r.amount ?: 0.0, 0.0001)
        assertEquals("交通", r.categoryName)
    }

    @Test
    fun `extractAmount parses mixed chinese numerals with 圆`() = runBlocking {
        val r = nlu.parse("午餐壹佰贰拾叁圆", 1L)
        assertEquals(123.0, r.amount ?: 0.0, 0.0001)
        assertEquals("三餐", r.categoryName)
    }

    // ── 2026-10-01 Task 3：建议可信度与模糊金额 ──

    private fun insertKeyword(keyword: String, category: String, priority: Int) {
        transaction {
            VoiceKeywordsTable.insert {
                it[VoiceKeywordsTable.userId] = 1L
                it[VoiceKeywordsTable.keyword] = keyword
                it[VoiceKeywordsTable.categoryName] = category
                it[VoiceKeywordsTable.priority] = priority
                it[VoiceKeywordsTable.createdAt] = "2026-01-01"
            }
        }
    }

    @Test
    fun `模糊金额三四十解析出区间与中值且需确认`() = runBlocking {
        val r = nlu.parse("打车三四十块", 1L)
        // 中值落账 + 原始区间（整数分）结构化给出
        assertEquals(35.0, r.amount ?: 0.0, 1e-9)
        assertTrue(r.fuzzyAmount)
        assertEquals(3000L, r.amountRangeMinMinor)
        assertEquals(4000L, r.amountRangeMaxMinor)
        assertTrue(r.amountNote!!.contains("区间30~40元"))
        assertTrue(r.amountNote!!.contains("按中值35元记"))
        // 规则词命中为 MEDIUM 且需确认；金额是估计值 → 一律不允许一键记入（HIGH 同样被压为 MEDIUM，见指代用例）
        assertEquals(SuggestionConfidence.MEDIUM, r.confidence)
        assertTrue(r.needsConfirmation)
        assertEquals(SuggestionSource.RULE, r.categorySource)
        // 解释含原始口语词与「按中值」说明，不把模糊金额说成精确金额
        assertTrue(r.explanation!!.contains("三四十"))
        assertTrue(r.explanation!!.contains("按中值35元记"))
    }

    @Test
    fun `模糊金额几十块解析出区间与中值`() = runBlocking {
        val r = nlu.parse("打车几十块", 1L)
        assertEquals(55.0, r.amount ?: 0.0, 1e-9)
        assertTrue(r.fuzzyAmount)
        assertEquals(2000L, r.amountRangeMinMinor)
        assertEquals(9000L, r.amountRangeMaxMinor)
        assertTrue(r.amountNote!!.contains("区间20~90元"))
        assertTrue(r.amountNote!!.contains("中值55"))
        assertTrue(r.needsConfirmation)
    }

    @Test
    fun `用户自定义关键词给出高置信度免确认建议`() = runBlocking {
        insertKeyword("星巴克", "日用", priority = 10)
        val r = nlu.parse("星巴克35元", 1L)
        assertEquals(SuggestionConfidence.HIGH, r.confidence)
        assertFalse("高置信度不需要确认，可一键记入", r.needsConfirmation)
        assertEquals(SuggestionSource.HISTORY, r.categorySource)
        assertTrue(r.explanation!!.contains("自定义词「星巴克」"))
        assertFalse(r.fuzzyAmount)
        assertNull(r.amountRangeMinMinor)
        assertNull(r.amountRangeMaxMinor)
    }

    @Test
    fun `历史学习关键词给出中等置信度需确认建议`() = runBlocking {
        // LLM 落账自动沉淀的学习词（priority=5）：有依据但不是用户亲口定义 → 中等置信度
        insertKeyword("星巴克", "日用", priority = 5)
        val r = nlu.parse("星巴克35元", 1L)
        assertEquals(SuggestionConfidence.MEDIUM, r.confidence)
        assertTrue(r.needsConfirmation)
        assertEquals(SuggestionSource.HISTORY, r.categorySource)
        assertTrue(r.explanation!!.contains("历史学习词「星巴克」"))
    }

    @Test
    fun `规则词命中标注规则来源与中置信度`() = runBlocking {
        // 用「打车」而非「午餐」：词表里更短的「餐」会先于「午餐」命中，导致解释里的关键词不是「午餐」
        val r = nlu.parse("打车15元", 1L)
        assertEquals(SuggestionSource.RULE, r.categorySource)
        assertEquals(SuggestionConfidence.MEDIUM, r.confidence)
        assertTrue(r.needsConfirmation)
        assertTrue(r.explanation!!.contains("规则词「打车」"))
    }

    @Test
    fun `无分类时为低置信度且需确认`() = runBlocking {
        val r = nlu.parse("买了个普通东西", 1L)
        assertEquals(SuggestionConfidence.LOW, r.confidence)
        assertNull(r.categorySource)
        assertTrue(r.needsConfirmation)
        // 保留字段：服务端暂不做账户推荐
        assertNull(r.accountSource)
    }

    @Test
    fun `精确金额不标模糊且区间为空`() = runBlocking {
        val r = nlu.parse("打车四十三块", 1L)
        assertEquals(43.0, r.amount ?: 0.0, 1e-9)
        assertFalse(r.fuzzyAmount)
        assertNull(r.amountRangeMinMinor)
        assertNull(r.amountRangeMaxMinor)
        assertNull(r.amountNote)
    }

    @Test
    fun `高置信度指代记账遇模糊金额压为中置信度`() = runBlocking {
        // 「跟上次一样」命中唯一记忆商家本为 HIGH，但金额是模糊区间 → 压为 MEDIUM 并要求确认
        UserMemoryService.recordBill(1L, "瑞幸咖啡9.9元", "饮品")
        UserMemoryService.recordBill(1L, "瑞幸咖啡12元", "饮品")
        val r = nlu.parse("跟上次一样三四十块", 1L)
        assertEquals(35.0, r.amount ?: 0.0, 1e-9)
        assertTrue(r.fuzzyAmount)
        assertEquals(SuggestionConfidence.MEDIUM, r.confidence)
        assertTrue(r.needsConfirmation)
        assertEquals(SuggestionSource.HISTORY, r.categorySource)
        // 两种依据都在：按上次记 + 按中值记
        assertTrue(r.amountNote!!.contains("按上次「瑞幸咖啡」记"))
        assertTrue(r.amountNote!!.contains("按中值35元记"))
    }
}
