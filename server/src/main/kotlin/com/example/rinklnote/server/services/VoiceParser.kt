package com.example.rinklnote.server.services

data class VoiceResult(
    val amount: Double?,
    val categoryName: String?,
    val remark: String
)

data class UserKeyword(
    val keyword: String,
    val categoryName: String
)
