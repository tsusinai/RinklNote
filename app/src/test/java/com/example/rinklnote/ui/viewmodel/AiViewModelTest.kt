package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AccountDTO
import com.example.rinklnote.data.network.dto.AiDisabledRequest
import com.example.rinklnote.data.network.dto.AnomalyAlert
import com.example.rinklnote.data.network.dto.CategoryAmount
import com.example.rinklnote.data.network.dto.MonthlySpike
import com.example.rinklnote.data.network.dto.MonthlyReviewResponse
import com.example.rinklnote.data.network.dto.HabitResponse
import com.example.rinklnote.data.network.dto.AnomalyResponse
import com.example.rinklnote.data.network.dto.BillDTO
import com.example.rinklnote.data.network.dto.BindQQRequest
import com.example.rinklnote.data.network.dto.BudgetDTO
import com.example.rinklnote.data.network.dto.BudgetSummaryDTO
import com.example.rinklnote.data.network.dto.DailyReportResponse
import com.example.rinklnote.data.network.dto.AiGenerateTokenRequest
import com.example.rinklnote.data.network.dto.AiTokenItem
import com.example.rinklnote.data.network.dto.AiTokenResponse
import com.example.rinklnote.data.repository.AccountRepository
import com.example.rinklnote.data.repository.ChatRepository
import com.example.rinklnote.navigation.BookingCommand
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.data.network.dto.ChangePasswordRequest
import com.example.rinklnote.data.network.dto.CreateAccountRequest
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
import com.example.rinklnote.data.network.dto.UpdateAccountRequest
import com.example.rinklnote.data.network.dto.UpsertBudgetRequest
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.data.repository.DailyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AiViewModel 聊天版测试：记账路由 + pendingBooking 握手、问账/异常路径、
 * 去重注入（欢迎语/月总结/异常）、路由判定（含数字的问句不误判为记账）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeBillRepository
    private lateinit var fake: FakeApiService
    private lateinit var accountRepo: FakeAccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeBillRepository()
        fake = FakeApiService()
        accountRepo = FakeAccountRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVM(): AiViewModel {
        val quick = QuickAddViewModel(repo, accountRepo, syncManager = null, api = fake)
        val vm = AiViewModel(
            fake,
            repo,
            { cmd -> if (cmd is BookingCommand.ParseAndBook) quick.onEvent(QuickAddEvent.VoiceUtterance(cmd.text)) }
        )
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `booking routes to quick add and marks pending`() = runTest(dispatcher) {
        val vm = newVM()
        vm.send("午餐28元")
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(28.0, repo.addedBills[0].amount, 0.0001)
        assertEquals("三餐", repo.addedBills[0].categoryName)
        assertTrue(vm.consumeBookingPending())
        assertFalse(vm.consumeBookingPending())
    }

    @Test
    fun `booking confirmation is appended as assistant message`() = runTest(dispatcher) {
        val vm = newVM()
        vm.send("午餐28元")
        advanceUntilIdle()
        vm.appendBookingConfirmed("已记账：28元（三餐）")
        advanceUntilIdle()

        val msg = repo.chatMessages.value.last { it.kind.value == "booking" }
        assertEquals("assistant", msg.role)
        assertEquals("已记账：28元（三餐）", msg.content)
    }

    @Test
    fun `question is answered by api`() = runTest(dispatcher) {
        val vm = newVM()
        vm.send("上个月交通花了多少")
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
        val msg = repo.chatMessages.value.last { it.role == "assistant" && it.kind.value == "text" }
        assertEquals(fake.queryResult.answer, msg.content)
    }

    @Test
    fun `month-digit question is not routed to booking`() = runTest(dispatcher) {
        val vm = newVM()
        vm.send("8月花了多少")
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
        val msg = repo.chatMessages.value.last { it.role == "assistant" && it.kind.value == "text" }
        assertEquals(fake.queryResult.answer, msg.content)
    }

    @Test
    fun `blank input is a no-op`() = runTest(dispatcher) {
        val vm = newVM()
        vm.send("   ")
        advanceUntilIdle()

        assertEquals(0, repo.chatMessages.value.size)
    }

    @Test
    fun `401 question maps to friendly login error`() = runTest(dispatcher) {
        fake.queryError = retrofit2.HttpException(
            retrofit2.Response.error<String>(401, "".toResponseBody(null))
        )
        val vm = newVM()
        vm.send("上个月花了多少")
        advanceUntilIdle()

        val msg = repo.chatMessages.value.last()
        assertTrue(msg.content.contains("登录"))
    }

    @Test
    fun `onEnter inserts greeting summary and anomaly once`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEnter(true)
        advanceUntilIdle()
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "greeting" })
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "summary" })
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "anomaly" })

        vm.onEnter(true)
        advanceUntilIdle()
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "greeting" })
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "summary" })
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "anomaly" })
    }

    @Test
    fun `onEnter logged out does not load summary or anomaly`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEnter(false)
        advanceUntilIdle()

        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "summary" })
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "anomaly" })
    }

    @Test
    fun `summary api failure does not consume dedup slot and retries on next entry`() = runTest(dispatcher) {
        fake.monthlyError = retrofit2.HttpException(
            retrofit2.Response.error<String>(500, "".toResponseBody(null))
        )
        val vm = newVM()
        vm.onEnter(true)
        advanceUntilIdle()

        // 失败时错误气泡用 kind=text，且 summary 去重位未被占用
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "summary" })
        assertTrue(
            repo.chatMessages.value.any {
                it.role == "assistant" && it.kind.value == "text" && it.content.contains("加载总结失败")
            }
        )

        // 下次进入重试成功
        fake.monthlyError = null
        vm.onEnter(true)
        advanceUntilIdle()
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "summary" })
    }

    @Test
    fun `habit is injected once per day when logged in`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEnter(true, false)
        advanceUntilIdle()
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "habit" })

        vm.onEnter(true, false)
        advanceUntilIdle()
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "habit" })
    }

    @Test
    fun `aiDisabled suppresses summary anomaly and habit but not greeting`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEnter(true, true)
        advanceUntilIdle()
        assertEquals(1, repo.chatMessages.value.count { it.kind.value == "greeting" })
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "summary" })
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "anomaly" })
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "habit" })
    }

    @Test
    fun `empty anomaly is not injected`() = runTest(dispatcher) {
        fake.anomalyResult = AnomalyResponse(alerts = emptyList())
        val vm = newVM()
        vm.onEnter(true, false)
        advanceUntilIdle()
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "anomaly" })
    }

    @Test
    fun `habit with null content is not injected`() = runTest(dispatcher) {
        fake.habitContent = null
        val vm = newVM()
        vm.onEnter(true, false)
        advanceUntilIdle()
        assertEquals(0, repo.chatMessages.value.count { it.kind.value == "habit" })
    }

    /** 仓库 fake：支持聊天 Flow + countSince 去重计数，同时驱动 QuickAdd 记账。 */
    private class FakeBillRepository : BillRepository, ChatRepository {
        override val expenseCategories: MutableStateFlow<List<Category>> = MutableStateFlow(
            listOf(Category(1, "三餐", "meals", BillType.EXPENSE), Category(2, "交通", "transport", BillType.EXPENSE))
        )
        override val incomeCategories: MutableStateFlow<List<Category>> = MutableStateFlow(
            listOf(Category(11, "工资", "salary", BillType.INCOME))
        )
        override val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(
            listOf(Account(1, "微信", 0.0, "#28C145"))
        )

        val addedBills = mutableListOf<Bill>()
        val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private var nextBillId = 100L
        private var nextChatId = 1L

        override suspend fun addBill(bill: Bill): Long {
            addedBills += bill
            return ++nextBillId
        }

        override fun observeChatMessages(): Flow<List<ChatMessage>> = chatMessages
        override suspend fun insertChatMessage(message: ChatMessage): Long {
            val m = message.copy(id = nextChatId++)
            chatMessages.value = chatMessages.value + m
            return m.id
        }
        override suspend fun countChatMessages(kind: String, since: Long): Long =
            chatMessages.value.count { it.kind.value == kind && it.createdAt >= since }.toLong()

        override fun observeAllBills(): Flow<List<Bill>> = flowOf(emptyList())
        override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> =
            flowOf(emptyList())
        override fun observeTemplates(): Flow<List<BillTemplate>> = flowOf(emptyList())
        override fun observeBudgets(): Flow<List<Budget>> = flowOf(emptyList())
        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double = 0.0
        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double = 0.0
        override suspend fun updateBill(bill: Bill) {}
        override suspend fun deleteBill(bill: Bill) {}
        override suspend fun updateAccount(account: Account) {}
        override fun observeAccounts(): Flow<List<Account>> = accounts
        override suspend fun insertAccount(account: Account): Long = 0
        override suspend fun updateAccountLocal(account: Account) {}
        override suspend fun softDeleteAccount(account: Account) {}
        override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun getUnsyncedAccounts(): List<Account> = emptyList()
        override suspend fun getAccountByServerId(serverId: Long): Account? = null
        override suspend fun deleteAccountByServerId(serverId: Long) {}
        override suspend fun getSubCategories(parentId: Long): List<SubCategory> = emptyList()
        override suspend fun getBudget(monthStart: Long): Budget? = null
        override suspend fun upsertBudget(budget: Budget) {}
        override suspend fun getUnsyncedBudgets(): List<Budget> = emptyList()
        override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun deleteBudgetByServerId(serverId: Long) {}
        override suspend fun clearLocalData() {}
        override suspend fun loadReferenceData() {}
        override suspend fun seedIfNeeded() {}
        override suspend fun getDailyReport(dayStart: Long, dayEnd: Long): DailyReport =
            DailyReport("", 0.0, 0.0, emptyList(), emptyList(), 0)
        override suspend fun countUnsynced(): Long = 0
        override suspend fun getAccountNet(accountId: Long): Double = 0.0
        override suspend fun reconcileAccount(account: Account, openingOffset: Double): Account = account
        override suspend fun reconcileAllAccounts(): List<Account> = emptyList()
    }

    /** ApiService fake：覆写 parseBill + insights 方法，其余桩。 */
    private class FakeApiService : ApiService {
        var reviewResult: MonthlyReviewResponse =
            MonthlyReviewResponse(
                summary = "本月支出 1234 元", highlights = listOf("餐饮占比 30%"),
                spikeDays = listOf(MonthlySpike("8/15", 200.0, 60)),
                topCategories = listOf(CategoryAmount("餐饮", 800.0))
            )
        var monthlyError: Exception? = null
        var anomalyResult: AnomalyResponse =
            AnomalyResponse(alerts = listOf(AnomalyAlert("HIGH", "周末支出异常偏高", "spike")))
        var queryResult: QueryResponse = QueryResponse(answer = "8 月交通共支出 156 元，共 12 笔。")
        var queryError: Exception? = null
        var habitContent: String? = "「午餐」你常记 三餐 ¥28.00，今天记了吗？"

        override suspend fun parseBill(request: ParseRequest): ParseResponse =
            ParseResponse(amount = "28", categoryName = "三餐", remark = request.text)

        override suspend fun getMonthlySummary(month: String): MonthlySummaryResponse =
            MonthlySummaryResponse()
        override suspend fun getMonthlyReview(month: String): MonthlyReviewResponse {
            monthlyError?.let { throw it }
            return reviewResult
        }
        override suspend fun getAnomalyAlerts(): AnomalyResponse = anomalyResult
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
        override suspend fun getBill(id: Long): BillDTO = BillDTO(0, 0.0, "", 0, "", null, 0, null, 0L, "", 0L)
        override suspend fun getAccounts(): List<AccountDTO> = emptyList()
        override suspend fun createAccount(request: CreateAccountRequest): AccountDTO =
            AccountDTO(0, "", 0.0, "")
        override suspend fun updateAccount(id: Long, request: UpdateAccountRequest): AccountDTO =
            AccountDTO(0, "", 0.0, "")
        override suspend fun deleteAccount(id: Long): MessageResponse = MessageResponse("")
        override suspend fun getBudgets(): List<BudgetDTO> = emptyList()
        override suspend fun upsertBudget(request: UpsertBudgetRequest): BudgetDTO = BudgetDTO(id = 0, monthStart = 0L, amount = 0.0, createdAt = 0L)
        override suspend fun getBudgetSummary(periodStart: Long): BudgetSummaryDTO =
            BudgetSummaryDTO(
                periodStart = periodStart,
                categoryBudgets = emptyList(),
                subCategoryBudgets = emptyList()
            )
        override suspend fun transcribe(file: MultipartBody.Part): TranscribeResponse = TranscribeResponse()
        override suspend fun getTemplates(): List<TemplateDTO> = emptyList()
        override suspend fun createTemplate(template: TemplateDTO): TemplateDTO =
            TemplateDTO(0, "", 0.0, 0, "", null, 0)
        override suspend fun deleteTemplate(id: Long): MessageResponse = MessageResponse("")
        override suspend fun getSuggestion(): Map<String, String> = emptyMap()
        override suspend fun getSuggestConfig(): Map<String, String> = emptyMap()
        override suspend fun updateSuggestConfig(config: Map<String, String>): MessageResponse = MessageResponse("")
        override suspend fun getAiDisabled(): Map<String, Boolean> = mapOf("disabled" to false)
        override suspend fun setAiDisabled(request: AiDisabledRequest): Map<String, Boolean> = mapOf("disabled" to request.disabled)
        override suspend fun getDailyReport(): DailyReportResponse =
            DailyReportResponse("", 0.0, 0.0, emptyList(), emptyList(), 0, "")
        override suspend fun generateAiToken(request: AiGenerateTokenRequest): AiTokenResponse =
            AiTokenResponse(0, "", "", 0)
        override suspend fun listAiTokens(): List<AiTokenItem> = emptyList()
        override suspend fun revokeAiToken(id: Long): MessageResponse = MessageResponse("")
        override suspend fun revokeAllAiTokens(): MessageResponse = MessageResponse("")
        override suspend fun getHabit(): HabitResponse = HabitResponse(habitContent)
    }

    private class FakeAccountRepository : AccountRepository {
        override fun observeAccounts(): Flow<List<Account>> = flowOf(emptyList())
        override suspend fun insertAccount(account: Account): Long = 0
        override suspend fun updateAccount(account: Account) {}
        override suspend fun updateAccountLocal(account: Account) {}
        override suspend fun softDeleteAccount(account: Account) {}
        override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun getUnsyncedAccounts(): List<Account> = emptyList()
        override suspend fun getAccountByServerId(serverId: Long): Account? = null
        override suspend fun deleteAccountByServerId(serverId: Long) {}
        override suspend fun getAccountNet(accountId: Long): Double = 0.0
        override suspend fun reconcileAccount(account: Account, openingOffset: Double): Account = account
        override suspend fun reconcileAllAccounts(): List<Account> = emptyList()
    }
}
