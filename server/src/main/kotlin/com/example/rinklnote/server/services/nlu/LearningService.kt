package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.tables.CorrectionLogTable
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class LearningService(
    private val llmParser: LLMParser,
    private val intervalMinutes: Long = 60,
    private val log: io.ktor.util.logging.Logger
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(intervalMinutes * 60 * 1000)
                try {
                    processCorrections()
                } catch (e: Exception) {
                    log.warn("LearningService processCorrections failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun processCorrections() {
        val unprocessed = transaction {
            CorrectionLogTable.selectAll()
                .where { CorrectionLogTable.processed eq false }
                .orderBy(CorrectionLogTable.id, SortOrder.ASC)
                .toList()
        }

        if (unprocessed.isEmpty()) return

        // Group by user
        val byUser = unprocessed.groupBy { it[CorrectionLogTable.userId] }

        for ((userId, records) in byUser) {
            val corrections = records.map {
                "'${it[CorrectionLogTable.originalText]}': ${it[CorrectionLogTable.originalCategory]} → ${it[CorrectionLogTable.correctedCategory]}"
            }

            val context = """
根据以下用户修正记录，提炼出关键词→分类的映射规则。

修正记录:
${corrections.joinToString("\n")}

请返回 JSON 格式的关键词规则列表:
{"rules": [{"keyword": "关键词", "categoryName": "分类名"}, ...]}

注意:
1. 关键词应是用户输入文本中能唯一标识分类的词语（2-4字）
2. 不要为输入文本中没有的词语创建关键词
3. 如果修正记录不足以提炼可靠规则，返回空列表
""".trimIndent()

            val categories = transaction {
                com.example.rinklnote.server.tables.CategoriesTable.selectAll().map { it[com.example.rinklnote.server.tables.CategoriesTable.name] }
            }

            val result = llmParser.chat(
                "你是一个关键词规则提炼助手。对每条修正记录，提取关键词→分类的映射。你必须返回 JSON: {\"rules\": [{\"keyword\": \"词\", \"categoryName\": \"分类\"}]}",
                context
            )

            // Parse rules from LLM response and save
            if (result != null) {
                saveRules(userId, records, result)
            }
        }
    }

    private fun saveRules(
        userId: Long,
        records: List<ResultRow>,
        jsonResponse: String
    ) {
        transaction {
            // Try to parse LLM-generated rules
            data class RuleEntry(val keyword: String, val categoryName: String)
            data class RulesResponse(val rules: List<RuleEntry>)

            val rules = try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                json.decodeFromString<RulesResponse>(jsonResponse).rules
            } catch (_: Exception) { emptyList() }

            if (rules.isNotEmpty()) {
                for (rule in rules) {
                    if (rule.keyword.isBlank()) continue
                    val exists = VoiceKeywordsTable.selectAll()
                        .where {
                            (VoiceKeywordsTable.userId eq userId) and
                            (VoiceKeywordsTable.keyword eq rule.keyword)
                        }.singleOrNull()

                    if (exists != null) {
                        VoiceKeywordsTable.update({ VoiceKeywordsTable.id eq exists[VoiceKeywordsTable.id] }) {
                            it[categoryName] = rule.categoryName
                            it[priority] = 8
                        }
                    } else {
                        VoiceKeywordsTable.insert {
                            it[VoiceKeywordsTable.userId] = userId
                            it[VoiceKeywordsTable.keyword] = rule.keyword
                            it[VoiceKeywordsTable.categoryName] = rule.categoryName
                            it[VoiceKeywordsTable.priority] = 8
                            it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                        }
                    }
                }
            } else {
                // Fallback: extract keyword from each correction record text
                for (record in records) {
                    val text = record[CorrectionLogTable.originalText]
                    val correctedCat = record[CorrectionLogTable.correctedCategory]
                    val keyword = text.take(6).trim()
                    if (keyword.length < 1) continue

                    val exists = VoiceKeywordsTable.selectAll()
                        .where {
                            (VoiceKeywordsTable.userId eq userId) and
                            (VoiceKeywordsTable.keyword eq keyword)
                        }.singleOrNull()

                    if (exists != null) {
                        VoiceKeywordsTable.update({ VoiceKeywordsTable.id eq exists[VoiceKeywordsTable.id] }) {
                            it[categoryName] = correctedCat
                            it[priority] = 8
                        }
                    } else {
                        VoiceKeywordsTable.insert {
                            it[VoiceKeywordsTable.userId] = userId
                            it[VoiceKeywordsTable.keyword] = keyword
                            it[VoiceKeywordsTable.categoryName] = correctedCat
                            it[VoiceKeywordsTable.priority] = 8
                            it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                        }
                    }
                }
            }

            // Mark all records as processed
            val recordIds = records.map { it[CorrectionLogTable.id] }
            CorrectionLogTable.update({ CorrectionLogTable.id inList recordIds }) {
                it[processed] = true
            }
        }
    }
}
