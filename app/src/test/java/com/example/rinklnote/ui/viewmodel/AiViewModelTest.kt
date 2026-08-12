package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AnomalyAlert
import com.example.rinklnote.data.network.dto.AnomalyResponse
import com.example.rinklnote.data.network.dto.BillDTO
import com.example.rinklnote.data.network.dto.BindQQRequest
import com.example.rinklnote.data.network.dto.BudgetDTO
import com.example.rinklnote.data.network.dto.ChangePasswordRequest
import com.example.rinklnote.data.network.dto.CreateBillRequest
import com.example.rinklnote.data.network.dto.LoginRequest
import com.example.rinklnote.data.network.dto.LoginResponse
import com.example.rinklnote.data.network.dto.MeResponse
import com.example.rinklnote.data.network.dto.MessageResponse
import com.example.rinklnote.data.network.dto.MonthlySummaryResponse
import com.example.rinklnote.data.network.dto.ParseRequest
import com.example.rinklnote.data.network.dto.ParseResponse
import com.example.rinklnote.data.network.dto.QueryRequest
import com.example.rinklnote.data.network.dto.QueryResponse
import com.example.rinklnote.data.network.dto.SyncResponse
import com.example.rinklnote.data.network.dto.TemplateDTO
import com.example.rinklnote.data.network.dto.TranscribeResponse
import com.example.rinklnote.data.network.dto.UpsertBudgetRequest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for AiViewModel: query/submit happy path & failure, blank no-op,
 * loadAll population, and the 401 → friendly login error mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var fake: FakeApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fake = FakeApiService()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVM(): AiViewModel {
        val vm = AiViewModel(fake)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `query sets answer on success`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(AiEvent.QueryInputChanged("上个月交通花多少"))
        vm.onEvent(AiEvent.SubmitQuery)
        advanceUntilIdle()

        assertEquals(fake.queryResult.answer, vm.state.value.answer)
        assertFalse(vm.state.value.isQuerying)
    }

    @Test
    fun `query surfaces error and clears answer on failure`() = runTest(dispatcher) {
        fake.queryError = IOException("network down")
        val vm = newVM()
        vm.onEvent(AiEvent.QueryInputChanged("上个月交通花多少"))
        vm.onEvent(AiEvent.SubmitQuery)
        advanceUntilIdle()

        val s = vm.state.value
        assertNotNull(s.error)
        assertNull(s.answer)
        assertFalse(s.isQuerying)
    }

    @Test
    fun `query with blank input is a no-op`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(AiEvent.QueryInputChanged("   "))
        vm.onEvent(AiEvent.SubmitQuery)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.isQuerying)
        assertNull(s.answer)
    }

    @Test
    fun `loadAll populates monthly summary and alerts`() = runTest(dispatcher) {
        val vm = newVM()
        vm.loadAll()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(fake.monthlyResult.summary, s.monthlySummary)
        assertEquals(fake.monthlyResult.highlights, s.highlights)
        assertEquals(fake.anomalyResult.alerts, s.alerts)
        assertFalse(s.isLoading)
    }

    @Test
    fun `unauthorized monthly maps to friendly login error`() = runTest(dispatcher) {
        val unauthorized = retrofit2.HttpException(
            retrofit2.Response.error<String>(401, "".toResponseBody(null))
        )
        // loadAnomaly's success path clears error, so both must fail with 401 for the
        // final state to surface the friendly login message.
        fake.monthlyError = unauthorized
        fake.anomalyError = unauthorized
        val vm = newVM()
        vm.loadAll()
        advanceUntilIdle()

        assertTrue(vm.state.value.error?.contains("登录") == true)
    }

    /** Hand-written ApiService fake — overrides only the 3 insights methods; stubs the rest. */
    private class FakeApiService : ApiService {

        var monthlyResult: MonthlySummaryResponse =
            MonthlySummaryResponse(summary = "本月支出 1234 元", highlights = listOf("餐饮占比 30%", "交通同比 -20%"))
        var anomalyResult: AnomalyResponse =
            AnomalyResponse(alerts = listOf(AnomalyAlert("HIGH", "周末支出异常偏高", "spike")))
        var queryResult: QueryResponse = QueryResponse(answer = "8 月交通共支出 156 元，共 12 笔。")
        var queryError: Exception? = null
        var monthlyError: Exception? = null
        var anomalyError: Exception? = null

        override suspend fun getMonthlySummary(month: String): MonthlySummaryResponse {
            monthlyError?.let { throw it }
            return monthlyResult
        }

        override suspend fun getAnomalyAlerts(): AnomalyResponse {
            anomalyError?.let { throw it }
            return anomalyResult
        }

        override suspend fun queryBillData(request: QueryRequest): QueryResponse {
            queryError?.let { throw it }
            return queryResult
        }

        // --- stubs for the rest of the interface ---
        override suspend fun register(request: LoginRequest): LoginResponse = LoginResponse(0, "")
        override suspend fun login(request: LoginRequest): LoginResponse = LoginResponse(0, "")
        override suspend fun bindQQ(request: BindQQRequest): MessageResponse = MessageResponse("")
        override suspend fun getMe(): MeResponse = MeResponse(0, "")
        override suspend fun changePassword(request: ChangePasswordRequest): MessageResponse = MessageResponse("")
        override suspend fun unbindQQ(): MessageResponse = MessageResponse("")
        override suspend fun syncBills(after: Long?, afterId: Long?, limit: Int): SyncResponse = SyncResponse(emptyList(), 0L)
        override suspend fun uploadBill(bill: CreateBillRequest): BillDTO = BillDTO(0, 0.0, "", 0, "", null, 0, null, 0L, "", 0L)
        override suspend fun updateBill(id: Long, bill: CreateBillRequest): BillDTO = BillDTO(0, 0.0, "", 0, "", null, 0, null, 0L, "", 0L)
        override suspend fun deleteBill(id: Long): MessageResponse = MessageResponse("")
        override suspend fun getBudgets(): List<BudgetDTO> = emptyList()
        override suspend fun upsertBudget(request: UpsertBudgetRequest): BudgetDTO = BudgetDTO(0, 0L, 0.0, 0L)
        override suspend fun parseBill(request: ParseRequest): ParseResponse = ParseResponse()
        override suspend fun transcribe(file: MultipartBody.Part): TranscribeResponse = TranscribeResponse()
        override suspend fun getTemplates(): List<TemplateDTO> = emptyList()
        override suspend fun createTemplate(template: TemplateDTO): TemplateDTO =
            TemplateDTO(0, "", 0.0, 0, "", null, 0)
        override suspend fun deleteTemplate(id: Long): MessageResponse = MessageResponse("")
        override suspend fun getSuggestion(): Map<String, String> = emptyMap()
        override suspend fun getSuggestConfig(): Map<String, String> = emptyMap()
        override suspend fun updateSuggestConfig(config: Map<String, String>): MessageResponse = MessageResponse("")
    }
}
