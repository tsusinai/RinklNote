package com.example.rinklnote.data.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class DailyReportResponse(
    val date: String,
    // 金额单位为「分」；旧元字段仅过渡期回退，勿使用。
    val totalExpenseMinor: Long = 0L,
    val totalExpense: Double = 0.0,
    val totalIncomeMinor: Long = 0L,
    val totalIncome: Double = 0.0,
    val expenseCategories: List<CategoryAmountDto>,
    val incomeCategories: List<CategoryAmountDto>,
    val billCount: Int,
    val summary: String
)

@Serializable
data class CategoryAmountDto(
    val name: String,
    val amountMinor: Long = 0L,
    val amount: Double = 0.0
)
