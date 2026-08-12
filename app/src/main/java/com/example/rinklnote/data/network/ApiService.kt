package com.example.rinklnote.data.network

import com.example.rinklnote.data.network.dto.*
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

    // Budgets
    @GET("api/budgets")
    suspend fun getBudgets(): List<BudgetDTO>

    @PUT("api/budgets")
    suspend fun upsertBudget(@Body request: UpsertBudgetRequest): BudgetDTO

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

    @GET("api/insights/anomaly")
    suspend fun getAnomalyAlerts(): AnomalyResponse

    @POST("api/insights/query")
    suspend fun queryBillData(@Body request: QueryRequest): QueryResponse
}
