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
data class MeResponse(
    val id: Long,
    val phone: String,
    val qqNumber: String? = null,
    val qqOpenid: String? = null,
    val createdAt: String? = null,
    val aiDisabled: Boolean = false,
    val dailyReportEnabled: Boolean = false,
    val dailyReportHour: Int = 9,
    val dailyReportMinute: Int = 0
)

/** 日报推送设置（QQ 端），对应 /api/auth/daily-report-setting。 */
@Serializable
data class DailyReportSettingDto(val enabled: Boolean, val hour: Int, val minute: Int)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class AiDisabledRequest(val disabled: Boolean)

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
    val hasMore: Boolean = false,
    val nextAfter: Long? = null,
    val nextAfterId: Long? = null
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
    val date: Long? = null,
    val baseUpdatedAt: Long? = null // 条件 PUT：带则要求等于服务端 updatedAt，否则 409
)

@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    val balance: Double,
    val iconColor: String,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class CreateAccountRequest(val name: String, val iconColor: String, val balance: Double = 0.0)

@Serializable
data class UpdateAccountRequest(val name: String? = null, val iconColor: String? = null, val balance: Double? = null)

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
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class UpsertBudgetRequest(
    val monthStart: Long,
    val amount: Double,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null
)

@Serializable
data class BudgetSummaryDTO(
    val periodStart: Long,
    val totalBudget: BudgetDTO? = null,
    val totalExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetDTO>,
    val subCategoryBudgets: List<SubCategoryBudgetDTO>,
    val lastMonthSurplus: Double? = null
)

@Serializable
data class CategoryBudgetDTO(
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,
    val expense: Double
)

@Serializable
data class SubCategoryBudgetDTO(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amount: Double,
    val expense: Double
)

@Serializable
data class TranscribeResponse(
    val text: String = "",
    val available: Boolean = true
)

@Serializable
data class QueryRequest(val query: String)

@Serializable
data class QueryResponse(val answer: String = "")

@Serializable
data class MonthlySummaryResponse(
    val summary: String = "",
    val highlights: List<String> = emptyList()
)

@Serializable
data class AnomalyResponse(val alerts: List<AnomalyAlert> = emptyList())

@Serializable
data class HabitResponse(val content: String? = null)

@Serializable
data class AnomalyAlert(
    val level: String = "",
    val message: String = "",
    val type: String = ""
)

@Serializable
data class CategoryAmount(val name: String = "", val amount: Double = 0.0)

@Serializable
data class MonthlySpike(val date: String = "", val amount: Double = 0.0, val ratioPct: Int = 0)

@Serializable
data class SingleBill(val amount: Double = 0.0, val categoryName: String = "", val date: String = "")

@Serializable
data class MonthlyReviewResponse(
    val month: String = "",
    val summary: String = "",
    val highlights: List<String> = emptyList(),
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val activeDays: Int = 0,
    val avgDailyExpense: Double = 0.0,
    val spikeDays: List<MonthlySpike> = emptyList(),
    val biggestSingle: SingleBill? = null,
    val topCategories: List<CategoryAmount> = emptyList()
)

// ── AI 助手接口：个人令牌 ──

@Serializable
data class AiGenerateTokenRequest(val name: String = "小爱")

@Serializable
data class AiTokenResponse(val id: Long, val token: String, val name: String, val createdAt: Long)

@Serializable
data class AiTokenItem(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val revoked: Boolean,
    val revokedAt: Long? = null
)
