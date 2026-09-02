package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, VoiceKeywordsTable
            )
            // Clear in FK dependency order so the shared in-memory DB is pristine per test.
            VoiceKeywordsTable.deleteAll()
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
}
