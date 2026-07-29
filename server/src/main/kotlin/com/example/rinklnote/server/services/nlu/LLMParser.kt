package com.example.rinklnote.server.services.nlu

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LLMParser(private val config: LLMParserConfig) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
        }
    }

    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val response_format: ChatResponseFormat? = null
    )

    @Serializable
    data class ChatResponseFormat(val type: String)

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    data class ChatResponse(
        val choices: List<ChatChoice>? = null
    )

    @Serializable
    data class ChatChoice(
        val message: ChatMessage? = null
    )

    /**
     * Build a system prompt listing available categories and instruction.
     */
    private fun buildSystemPrompt(categories: List<String>): String {
        val catList = categories.joinToString("、")
        return """
你是一个记账分类助手。根据用户的输入文本，选择最合适的分类，并提取备注信息。

可用分类: $catList

规则:
1. 如果输入明确提到某个分类的关键词，选择该分类
2. 如果没有明确关键词，根据语义推断最合适的分类
3. 如果完全无法判断，选择"三餐"作为默认分类
4. 提取金额之外的描述性文字作为 remark（不要包含金额数字）
5. 如果输入中有类似子分类的信息（如"午餐"算三餐的子分类），填到 subCategoryName

你必须返回一个 JSON 对象:
{"categoryName": "分类名", "subCategoryName": "子分类名或null", "remark": "备注文字或null"}
""".trimIndent()
    }

    /**
     * Call DeepSeek API to parse the input text.
     * Returns null on failure (timeout, error, invalid response).
     */
    suspend fun parse(text: String, categories: List<String>, extraContext: String? = null): LLMParseResult? {
        val userContent = buildString {
            append("用户输入: \"$text\"")
            if (!extraContext.isNullOrBlank()) {
                append("\n$extraContext")
            }
        }

        val request = ChatRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(categories)),
                ChatMessage(role = "user", content = userContent)
            ),
            response_format = ChatResponseFormat("json_object")
        )

        return try {
            val response: ChatResponse = client.post("${config.baseUrl}/v1/chat/completions") {
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val content = response.choices?.firstOrNull()?.message?.content ?: return null
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            json.decodeFromString<LLMParseResult>(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * General-purpose chat method for insight/summary use cases.
     * Takes full system prompt and user message, returns JSON string response.
     */
    suspend fun chat(systemPrompt: String, userMessage: String): String? {
        val request = ChatRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage)
            ),
            response_format = ChatResponseFormat("json_object")
        )

        return try {
            val response: ChatResponse = client.post("${config.baseUrl}/v1/chat/completions") {
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            response.choices?.firstOrNull()?.message?.content
        } catch (e: Exception) {
            null
        }
    }

    fun shutdown() {
        client.close()
    }
}
