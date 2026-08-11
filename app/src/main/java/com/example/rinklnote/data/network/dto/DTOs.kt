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
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class SyncResponse(
    val bills: List<BillDTO>,
    val serverTime: Long,
    val hasMore: Boolean = false
)

@Serializable
data class CreateBillRequest(
    val amount: Double,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String? = null,
    val accountId: Long,
    val remark: String? = null,
    val date: Long? = null
)

@Serializable
data class TemplateDTO(
    val id: Long = 0, val label: String, val amount: Double,
    val categoryId: Long, val categoryName: String,
    val subCategoryName: String? = null, val accountId: Long,
    val sortOrder: Int = 0
)

@Serializable
data class ParseRequest(val text: String)

@Serializable
data class ParseResponse(
    val amount: String = "",
    val categoryName: String = "",
    val remark: String = "",
    val subCategoryName: String = "",
    val message: String = ""
)

@Serializable
data class BudgetDTO(
    val id: Long,
    val monthStart: Long,
    val amount: Double,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class UpsertBudgetRequest(
    val monthStart: Long,
    val amount: Double
)

@Serializable
data class ReorderRequest(val ids: List<Long>)
