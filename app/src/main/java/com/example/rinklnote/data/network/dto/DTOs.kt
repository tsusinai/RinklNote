package com.example.rinklnote.data.network.dto

import com.example.rinklnote.util.Money
import com.example.rinklnote.domain.resolveAccountIconKey
import kotlinx.serialization.Serializable

/**
 * 登录 / 注册请求（2026-09-17 优化登录方式）：新增可选 email 身份，与 phone 同级 ——
 * email 非空走邮箱身份（此时 phone 传空串），否则走手机号（旧语义不变）。
 * 默认值 null 保证旧序列化行为不变（email 为空时该键可省略）。
 */
@Serializable
data class LoginRequest(
    val phone: String = "",
    val password: String,
    val email: String? = null
)

@Serializable
data class LoginResponse(val userId: Long, val token: String)

/** 机器人绑定码请求：对应 /api/{qq,feishu,wecom}-bot/bind 的 `{"code": "6位码"}`。 */
@Serializable
data class BotBindCodeRequest(val code: String)

/**
 * 机器人绑定状态：对应 /api/{qq,feishu,wecom}-bot/bind-status。
 * 服务端 `bound` 以字符串布尔下发（照 QQ 管理路由现状）；掩码取通道身份后 6 位，
 * QQ 返回字段名 `openid`，飞书/企微返回 `openId`，两者取其一。
 */
@Serializable
data class BotBindStatusResponse(
    val bound: String = "false",
    val openid: String? = null,
    val openId: String? = null,
    val message: String? = null
) {
    val isBound: Boolean get() = bound == "true"
    /** 掩码身份（后 6 位），未绑定为空串。 */
    val maskedId: String get() = openid ?: openId ?: ""
}

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class MeResponse(
    val id: Long,
    // phone 可空（默认 null）：纯邮箱注册 / QQ openid 开户的用户没有手机号，服务端该键为 null 或缺省。
    val phone: String? = null,
    // 邮箱身份（2026-09-17）：可空，未设置 / 旧服务端不下发时为 null。
    val email: String? = null,
    val qqNumber: String? = null,
    val qqOpenid: String? = null,
    val createdAt: String? = null,
    val aiDisabled: Boolean = false,
    val dailyReportEnabled: Boolean = false,
    val dailyReportHour: Int = 9,
    val dailyReportMinute: Int = 0,
    // ── 个人资料（2026-09-17）──
    // 新增可空字段默认 null：旧服务端不下发这些键时解析仍成立（ignoreUnknownKeys + 默认值）。
    // showcaseBadges 以服务端存储原样下发（逗号分隔徽章 key），展示端与成就解锁态取交集。
    val nickname: String? = null,
    val signature: String? = null,
    val birthday: String? = null,
    // 头像相对 URL（如 "uploads/avatars/3.jpg?v=123"）；拼 RetrofitClient.BASE_URL 即完整地址。
    val avatarUrl: String? = null,
    val showcaseBadges: String? = null
)

/**
 * PUT /api/auth/profile 请求体：文字资料**整体替换**（null = 清除该字段）。
 * 头像不走该接口，走 POST /api/auth/avatar multipart 上传。
 */
@Serializable
data class ProfileUpdateRequest(
    val nickname: String? = null,
    val signature: String? = null,
    val birthday: String? = null,
    // 展示徽章 key 列表（上限 3）；空列表 = 清空展示。
    val showcaseBadges: List<String> = emptyList()
)

/** POST /api/auth/avatar 响应：回传最新头像相对 URL（含 ?v= 版本参数）。 */
@Serializable
data class AvatarUploadResponse(
    val avatarUrl: String? = null,
    val message: String? = null
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
    // 金额（分，权威值）
    val amountMinor: Long? = null,
    // 旧字段（元）：服务端过渡期仍会下发，新客户端只读 amountMinor，勿使用。
    val amount: Double? = null,
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
    val deleted: Boolean = false,
    // 同日内显式排序名次（拖动重排）；null = 未排序（查询端 COALESCE(created_at) 兜底）。
    val sortOrder: Long? = null,
    // 经纬度（度）：仅用户主动打点的账单才有值；null 序列化时省略，
    // 旧服务端（无该字段）/旧客户端（ignoreUnknownKeys）互不影响。
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    /** 解析金额：优先取分；旧服务端只回 amount（元）时回退换算。 */
    val resolvedAmountMinor: Long
        get() = amountMinor ?: Money.yuanToMinor(amount ?: 0.0)
}

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
    // 只发「分」（服务端权威字段）。
    val amountMinor: Long,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String? = null,
    val accountId: Long,
    val remark: String? = null,
    val date: Long? = null,
    val baseUpdatedAt: Long? = null, // 条件 PUT：带则要求等于服务端 updatedAt，否则 409
    val sortOrder: Long? = null, // 同日内显式排序名次（拖动重排）
    // 经纬度（度）：用户主动打点才有值；null 时服务端存 NULL（清除打点同样靠传 null 生效）。
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    // 余额（分，权威值）
    val balanceMinor: Long? = null,
    // 旧字段（元）：过渡期兼容，勿使用。
    val balance: Double? = null,
    val iconColor: String,
    /** 旧服务端可能缺失；读取时按名称回退。 */
    val iconKey: String? = null,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
) {
    /** 解析余额：优先取分，旧服务端只回 balance（元）时回退换算。 */
    val resolvedBalanceMinor: Long
        get() = balanceMinor ?: Money.yuanToMinor(balance ?: 0.0)

    val resolvedIconKey: String
        get() = resolveAccountIconKey(iconKey, name)
}

@Serializable
data class CreateAccountRequest(
    val name: String,
    val iconColor: String,
    val balanceMinor: Long = 0L,
    val iconKey: String = "WALLET"
)

@Serializable
data class UpdateAccountRequest(
    val name: String? = null,
    val iconColor: String? = null,
    val balanceMinor: Long? = null,
    val iconKey: String? = null
)

@Serializable
data class TemplateDTO(
    val id: Long = 0, val label: String,
    // 金额（分，权威值）；旧字段 amount（元）仅在旧服务端回退时使用。
    val amountMinor: Long? = null,
    val amount: Double? = null,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String? = null, val accountId: Long,
    val sortOrder: Int = 0
) {
    val resolvedAmountMinor: Long
        get() = amountMinor ?: Money.yuanToMinor(amount ?: 0.0)
}

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
    // 金额（分，权威值）；旧字段 amount（元）仅回退用。
    val amountMinor: Long? = null,
    val amount: Double? = null,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
) {
    val resolvedAmountMinor: Long
        get() = amountMinor ?: Money.yuanToMinor(amount ?: 0.0)
}

@Serializable
data class UpsertBudgetRequest(
    val monthStart: Long,
    // 只发「分」（服务端权威字段）。
    val amountMinor: Long,
    val periodType: String = "MONTHLY",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null
)

@Serializable
data class BudgetSummaryDTO(
    val periodStart: Long,
    val totalBudget: BudgetDTO? = null,
    val totalExpenseMinor: Long = 0L,
    val totalExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetDTO>,
    val subCategoryBudgets: List<SubCategoryBudgetDTO>,
    val lastMonthSurplusMinor: Long? = null,
    val lastMonthSurplus: Double? = null
)

@Serializable
data class CategoryBudgetDTO(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long = 0L,
    val expenseMinor: Long = 0L,
    val amount: Double = 0.0,
    val expense: Double = 0.0
)

@Serializable
data class SubCategoryBudgetDTO(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amountMinor: Long = 0L,
    val expenseMinor: Long = 0L,
    val amount: Double = 0.0,
    val expense: Double = 0.0
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

// 洞察相关金额同样以「分」为准：新字段 amountMinor / totalXxxMinor 优先，旧元字段仅回退。
@Serializable
data class CategoryAmount(
    val name: String = "",
    val amountMinor: Long = 0L,
    val amount: Double = 0.0
)

@Serializable
data class MonthlySpike(
    val date: String = "",
    val amountMinor: Long = 0L,
    val amount: Double = 0.0,
    val ratioPct: Int = 0
)

@Serializable
data class SingleBill(
    val amountMinor: Long = 0L,
    val amount: Double = 0.0,
    val categoryName: String = "",
    val date: String = ""
)

@Serializable
data class MonthlyReviewResponse(
    val month: String = "",
    val summary: String = "",
    val highlights: List<String> = emptyList(),
    val totalExpenseMinor: Long = 0L,
    val totalExpense: Double = 0.0,
    val totalIncomeMinor: Long = 0L,
    val totalIncome: Double = 0.0,
    val activeDays: Int = 0,
    val avgDailyExpenseMinor: Long = 0L,
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
