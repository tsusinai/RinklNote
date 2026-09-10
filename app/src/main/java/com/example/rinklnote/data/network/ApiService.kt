package com.example.rinklnote.data.network

import com.example.rinklnote.data.network.dto.*
import com.example.rinklnote.data.network.dto.DailyReportResponse
import okhttp3.MultipartBody
import retrofit2.http.*

interface ApiService {
    @POST("api/auth/register")
    suspend fun register(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/bind-qq")
    suspend fun bindQQ(@Body request: BindQQRequest): MessageResponse

    @GET("api/auth/me")
    suspend fun getMe(): MeResponse

    @POST("api/auth/password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): MessageResponse

    @POST("api/auth/unbind-qq")
    suspend fun unbindQQ(): MessageResponse

    @GET("api/auth/ai")
    suspend fun getAiDisabled(): Map<String, Boolean>

    @PUT("api/auth/ai")
    suspend fun setAiDisabled(@Body request: AiDisabledRequest): Map<String, Boolean>

    // 日报推送设置（QQ 端）。注意与 getDailyReport()（拉日报内容）区分。
    @GET("api/auth/daily-report-setting")
    suspend fun getDailyReportSetting(): DailyReportSettingDto

    @PUT("api/auth/daily-report-setting")
    suspend fun setDailyReportSetting(@Body request: DailyReportSettingDto): DailyReportSettingDto

    @GET("api/bills/sync")
    suspend fun syncBills(
        @Query("after") after: Long? = null,
        @Query("afterId") afterId: Long? = null,
        @Query("limit") limit: Int = 200
    ): SyncResponse

    @POST("api/bills")
    suspend fun uploadBill(@Body bill: CreateBillRequest): BillDTO

    @PUT("api/bills/{id}")
    suspend fun updateBill(@Path("id") id: Long, @Body bill: CreateBillRequest): BillDTO

    @DELETE("api/bills/{id}")
    suspend fun deleteBill(@Path("id") id: Long): MessageResponse

    @GET("api/bills/{id}")
    suspend fun getBill(@Path("id") id: Long): BillDTO

    // Accounts — per-user, JWT-protected
    @GET("api/accounts")
    suspend fun getAccounts(): List<AccountDTO>

    @POST("api/accounts")
    suspend fun createAccount(@Body request: CreateAccountRequest): AccountDTO

    @PUT("api/accounts/{id}")
    suspend fun updateAccount(@Path("id") id: Long, @Body request: UpdateAccountRequest): AccountDTO

    @DELETE("api/accounts/{id}")
    suspend fun deleteAccount(@Path("id") id: Long): MessageResponse

    // Budgets
    @GET("api/budgets")
    suspend fun getBudgets(): List<BudgetDTO>

    @PUT("api/budgets")
    suspend fun upsertBudget(@Body request: UpsertBudgetRequest): BudgetDTO

    @GET("api/budgets/summary")
    suspend fun getBudgetSummary(@Query("periodStart") periodStart: Long): BudgetSummaryDTO

    @POST("api/bills/parse")
    suspend fun parseBill(@Body request: ParseRequest): ParseResponse

    // Speech-to-text: upload recorded audio, get transcript.
    // Server returns {available:false} when no ASR is configured → client falls back to on-device.
    @Multipart
    @POST("api/bills/transcribe")
    suspend fun transcribe(@Part file: MultipartBody.Part): TranscribeResponse

    // Templates
    @GET("api/templates")
    suspend fun getTemplates(): List<TemplateDTO>

    @POST("api/templates")
    suspend fun createTemplate(@Body template: TemplateDTO): TemplateDTO

    @DELETE("api/templates/{id}")
    suspend fun deleteTemplate(@Path("id") id: Long): MessageResponse

    // Suggestion
    @GET("api/insights/suggest")
    suspend fun getSuggestion(): Map<String, String>

    @GET("api/insights/suggest-config")
    suspend fun getSuggestConfig(): Map<String, String>

    @PUT("api/insights/suggest-config")
    suspend fun updateSuggestConfig(@Body config: Map<String, String>): MessageResponse

    // Insights (综合助手) — JWT-protected
    @GET("api/insights/monthly")
    suspend fun getMonthlySummary(@Query("month") month: String): MonthlySummaryResponse

    @GET("api/insights/monthly-review")
    suspend fun getMonthlyReview(@Query("month") month: String): MonthlyReviewResponse

    @GET("api/insights/anomaly")
    suspend fun getAnomalyAlerts(): AnomalyResponse

    @POST("api/insights/query")
    suspend fun queryBillData(@Body request: QueryRequest): QueryResponse

    @GET("api/insights/daily-report")
    suspend fun getDailyReport(): DailyReportResponse

    @GET("api/insights/habit")
    suspend fun getHabit(): HabitResponse

    // AI 助手接口：个人令牌管理（JWT）
    @POST("api/ai/tokens")
    suspend fun generateAiToken(@Body request: AiGenerateTokenRequest): AiTokenResponse

    @GET("api/ai/tokens")
    suspend fun listAiTokens(): List<AiTokenItem>

    @POST("api/ai/tokens/{id}/revoke")
    suspend fun revokeAiToken(@Path("id") id: Long): MessageResponse

    @POST("api/ai/tokens/revoke-all")
    suspend fun revokeAllAiTokens(): MessageResponse
}
