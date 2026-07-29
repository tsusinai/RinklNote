package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.UserKeyword
import com.example.rinklnote.server.services.VoiceParser
import com.example.rinklnote.server.services.VoiceResult
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class DefaultNLUService(
    private val ruleBasedParser: RuleBasedParser,
    private val llmParser: LLMParser,
    private val billService: BillService
) : NLUService {

    override suspend fun parse(text: String, userId: Long): VoiceResult {
        // Step 1: extract amount (reuse VoiceParser's regex)
        val amount = extractAmount(text)

        // Step 2: lookup user custom keywords from DB
        val userKeywords = getUserKeywords(userId)

        // Step 3: try rule-based match (user keywords → system keywords)
        val categoryName = ruleBasedParser.parse(text, userKeywords)

        if (categoryName != null) {
            return VoiceResult(amount = amount, categoryName = categoryName, remark = text)
        }

        // Step 4: LLM fallback
        val categories = transaction {
            billService.getCategories().map { it.name }
        }
        val llmResult = llmParser.parse(text, categories)

        if (llmResult != null && llmResult.categoryName != null) {
            // Auto-save learned keyword (priority=5, lower than manual default 10)
            saveLearnedKeyword(userId, text, llmResult.categoryName)

            val remark = llmResult.remark ?: text
            return VoiceResult(
                amount = amount,
                categoryName = llmResult.categoryName,
                remark = remark
            )
        }

        // Ultimate fallback: return without category
        return VoiceResult(amount = amount, categoryName = null, remark = text)
    }

    private fun extractAmount(text: String): Double? {
        val pattern = Regex("""(\d+\.?\d*)\s*[元块]?""")
        return pattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun getUserKeywords(userId: Long): List<UserKeyword> {
        return transaction {
            VoiceKeywordsTable.selectAll()
                .where { VoiceKeywordsTable.userId eq userId }
                .orderBy(VoiceKeywordsTable.priority, SortOrder.DESC)
                .map {
                    UserKeyword(
                        keyword = it[VoiceKeywordsTable.keyword],
                        categoryName = it[VoiceKeywordsTable.categoryName]
                    )
                }
        }
    }

    private fun saveLearnedKeyword(userId: Long, text: String, categoryName: String) {
        // Extract a keyword fragment from text (take first 2-6 chars as keyword)
        val keyword = text.take(6).trim()
        if (keyword.length < 1) return

        transaction {
            val existing = VoiceKeywordsTable.selectAll()
                .where {
                    (VoiceKeywordsTable.userId eq userId) and
                    (VoiceKeywordsTable.keyword eq keyword)
                }.singleOrNull()

            if (existing == null) {
                VoiceKeywordsTable.insert {
                    it[VoiceKeywordsTable.userId] = userId
                    it[VoiceKeywordsTable.keyword] = keyword
                    it[VoiceKeywordsTable.categoryName] = categoryName
                    it[VoiceKeywordsTable.priority] = 5
                    it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                }
            }
        }
    }
}
