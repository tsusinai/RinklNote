package com.example.rinklnote.data.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseReportRequest(
    val type: String,
    val month: String? = null,
    val query: String? = null,
    val onlyIfAnomalous: Boolean = false
)

@Serializable
data class ExpenseReportResponse(
    val format: String,
    val type: String,
    val title: String,
    val month: String,
    val html: String,
    val plainText: String,
    val source: String,
    val totalExpenseMinor: Long,
    val expenseCount: Long,
    val hasAnomaly: Boolean
)
