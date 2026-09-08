package com.example.rinklnote.data.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class DailyReportResponse(
    val date: String,
    val totalExpense: Double,
    val totalIncome: Double,
    val expenseCategories: List<CategoryAmountDto>,
    val incomeCategories: List<CategoryAmountDto>,
    val billCount: Int,
    val summary: String
)

@Serializable
data class CategoryAmountDto(
    val name: String,
    val amount: Double
)
