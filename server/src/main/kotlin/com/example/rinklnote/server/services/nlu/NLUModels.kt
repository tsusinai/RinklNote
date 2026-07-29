package com.example.rinklnote.server.services.nlu

import kotlinx.serialization.Serializable

@Serializable
data class LLMParseResult(
    val categoryName: String? = null,
    val subCategoryName: String? = null,
    val remark: String? = null
)

data class LLMParserConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val timeoutMs: Long = 10_000
)
