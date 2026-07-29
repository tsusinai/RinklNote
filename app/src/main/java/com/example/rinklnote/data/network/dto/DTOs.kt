package com.example.rinklnote.data.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val phone: String, val password: String)

@Serializable
data class LoginResponse(val userId: Long, val token: String)

@Serializable
data class BindQQRequest(val qqNumber: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class BillDTO(
    val id: Long,
    val amount: Double,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String? = null,
    val accountId: Long,
    val remark: String? = null,
    val date: Long,
    val source: String,
    val createdAt: Long
)

@Serializable
data class SyncResponse(
    val bills: List<BillDTO>,
    val serverTime: Long
)
