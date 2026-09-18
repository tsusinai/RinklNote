package com.example.rinklnote.server.services

data class VoiceResult(
    val amount: Double?,
    val categoryName: String?,
    val remark: String,
    // 2026-09-18 Task 1.3（NLU 理解升级）：模糊金额区间的回执标注（如「区间30~40元，按中值35元记」）。
    val amountNote: String? = null,
    // 非空时路由层直接把该文案回给用户（上下文指代「跟上次一样」的追问/引导），跳过记账与 LLM。
    val askReply: String? = null
)

data class UserKeyword(
    val keyword: String,
    val categoryName: String
)
