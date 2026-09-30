package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.ParseResponse
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.repository.AccountRepository
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.data.repository.DailyReport
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.ui.component.BillFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * JVM unit tests for the QuickAdd two-phase state machine (Phase 4):
 * digit/backspace amount building, confirm gating, local NLP fallback,
 * the finalConfirm double-submit guard and resetConfirming release.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeBillRepository
    private lateinit var accountRepo: FakeAccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeBillRepository()
        accountRepo = FakeAccountRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Constructs the VM with a null syncManager/api and lets init collectors run. */
    private fun TestScope.newVM(): QuickAddViewModel {
        val vm = QuickAddViewModel(repo, accountRepo, syncManager = null, api = null)
        advanceUntilIdle()
        return vm
    }

    /** 手动记账必须由用户显式选择标签和账户，测试保存路径时先补齐两项。 */
    private fun TestScope.newReadyVM(): QuickAddViewModel {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.SelectCategory(repo.expenseCategories.value.first()))
        vm.onEvent(QuickAddEvent.SelectAccount(accountRepo.accounts.value.first()))
        return vm
    }

    /** 构造带打桩 SyncManager 的就绪 VM：pushBill 固定返回 [pushResult]（反馈判定用）。 */
    private suspend fun TestScope.newReadyVMWithSync(pushResult: SyncResult): QuickAddViewModel {
        val sync = mock<SyncManager>()
        whenever(sync.pushBill(any())).thenReturn(pushResult)
        val vm = QuickAddViewModel(repo, accountRepo, syncManager = sync, api = null)
        advanceUntilIdle()
        vm.onEvent(QuickAddEvent.SelectCategory(repo.expenseCategories.value.first()))
        vm.onEvent(QuickAddEvent.SelectAccount(accountRepo.accounts.value.first()))
        return vm
    }

    /** 构造带打桩 ApiService 的 VM：parse / suggest 结果由用例注入（Task 3 建议确认层）。 */
    private fun TestScope.newVMWithApi(api: ApiService): QuickAddViewModel {
        val vm = QuickAddViewModel(repo, accountRepo, syncManager = null, api = api)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `manual quick add starts without default category or account`() = runTest(dispatcher) {
        val vm = newVM()

        assertNull(vm.state.value.selectedCategory)
        assertNull(vm.state.value.selectedAccount)
    }

    @Test
    fun `parentIdsWithSubs loads for the subcategory hint marker`() = runTest(dispatcher) {
        repo.allSubCategories = listOf(
            SubCategory(name = "早餐", parentCategoryId = 1L),
            SubCategory(name = "午餐", parentCategoryId = 1L),
            SubCategory(name = "基本工资", parentCategoryId = 11L)
        )
        val vm = newVM()

        assertEquals(setOf(1L, 11L), vm.state.value.parentIdsWithSubs)
    }

    @Test
    fun `digit backspace clear builds the amount string`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("1"))
        vm.onEvent(QuickAddEvent.Digit("0"))
        assertEquals("10", vm.state.value.amount)

        vm.onEvent(QuickAddEvent.Digit("0"))
        assertEquals("100", vm.state.value.amount)

        vm.onEvent(QuickAddEvent.Backspace)
        assertEquals("10", vm.state.value.amount)

        vm.onEvent(QuickAddEvent.Clear)
        assertEquals("", vm.state.value.amount)

        vm.onEvent(QuickAddEvent.Digit("0"))
        assertEquals("0", vm.state.value.amount)
    }

    @Test
    fun `confirm saves the bill directly`() = runTest(dispatcher) {
        val vm = newReadyVM()
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(2000L, repo.addedBills[0].amountMinor)
        assertEquals("三餐", repo.addedBills[0].categoryName)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
    }

    @Test
    fun `confirm without account saves nothing`() = runTest(dispatcher) {
        accountRepo.accounts.value = emptyList()
        val vm = newVM()
        vm.onEvent(QuickAddEvent.SelectCategory(repo.expenseCategories.value.first()))
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
        assertEquals(
            QuickAddEffect.FinalConfirmFailed("请先选择账户"),
            vm.effects.first()
        )
    }

    @Test
    fun `confirm without tag or account reports both missing`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
        assertEquals(
            QuickAddEffect.FinalConfirmFailed("请先选择标签和账户"),
            vm.effects.first()
        )
    }

    @Test
    fun `confirm with missing amount is ignored`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(0, repo.addedBills.size)
    }

    @Test
    fun `nlp local parse fills amount and category and saves`() = runTest(dispatcher) {
        val vm = newReadyVM() // api = null → local VoiceParser fallback
        vm.onEvent(QuickAddEvent.NlpInput("午餐20元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("20", s.amount)
        assertEquals("三餐", s.selectedCategory?.name)
        assertEquals("", s.nlpInput)
        assertFalse(s.isParsing)
        assertEquals(1, repo.addedBills.size)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
    }

    @Test
    fun `nlp local parse without a recognized category does not save`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.NlpInput("普通消费30元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("30", s.amount)
        assertNull(s.selectedCategory)
        assertEquals(0, repo.addedBills.size)
    }

    @Test
    fun `finalConfirm saves exactly one bill and releases the guard`() = runTest(dispatcher) {
        val vm = newReadyVM()
        vm.onEvent(QuickAddEvent.Digit("25"))

        vm.finalConfirm()
        vm.finalConfirm() // second call while guard is set must be ignored
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(2500L, repo.addedBills[0].amountMinor)
        assertEquals("三餐", repo.addedBills[0].categoryName)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())

        // Guard released after save → next submission works.
        vm.onEvent(QuickAddEvent.Digit("5"))
        vm.finalConfirm()
        advanceUntilIdle()
        assertEquals(2, repo.addedBills.size)
    }

    @Test
    fun `resetConfirming clears a pending guard`() = runTest(dispatcher) {
        val vm = newReadyVM()
        vm.onEvent(QuickAddEvent.Digit("9"))
        vm.finalConfirm() // guard set, async save queued
        vm.resetConfirming() // Phase 1 fix: release before async completes
        vm.onEvent(QuickAddEvent.Digit("9"))
        vm.finalConfirm()
        advanceUntilIdle()

        assertEquals(2, repo.addedBills.size)
    }

    // ── 体验优化 Task 2：结构化反馈（保存 → 云端结果升级）──

    @Test
    fun `保存成功后先报已保存到本机再升级为已同步到云端`() = runTest(dispatcher) {
        val vm = newReadyVMWithSync(SyncResult.Success(1, 0))
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        // 先本地落库反馈，再按 SyncManager 的推送结果（与同步状态流同源）升级为云端反馈。
        assertEquals(BillFeedback.SavedLocally(), vm.feedback.first())
        assertEquals(BillFeedback.SyncedToCloud(), vm.feedback.first())
        // 一步确认语义不变：确认即落库关抽屉，FinalConfirmCompleted 效果照发。
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
    }

    @Test
    fun `推送失败时反馈同步失败但账单仍保存在本机并可重试`() = runTest(dispatcher) {
        val vm = newReadyVMWithSync(SyncResult.Error("网络不可用，请检查网络后重试"))
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(BillFeedback.SavedLocally(), vm.feedback.first())
        val failed = vm.feedback.first()
        assertTrue("feedback was $failed", failed is BillFeedback.SyncFailedRetained)
        failed as BillFeedback.SyncFailedRetained
        assertEquals("网络不可用，请检查网络后重试", failed.reason)
        assertEquals("重试", failed.actionLabel)
        assertTrue("失败类反馈保持显示直到用户操作或关闭", failed.persistent)
        // 同步失败不丢数据：账单已落库。
        assertEquals(1, repo.addedBills.size)
    }

    @Test
    fun `未登录时保存仅反馈已保存到本机`() = runTest(dispatcher) {
        val vm = newReadyVMWithSync(SyncResult.NotLoggedIn)
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(BillFeedback.SavedLocally(), vm.feedback.first())
        // 未登录不追加失败提示：100ms（虚拟时间）内没有新反馈即为空。
        assertTrue(runCatching { withTimeout(100) { vm.feedback.first() } }.isFailure)
    }

    // ── 体验优化 Task 3：建议可信度与确认层 ──

    @Test
    fun `高置信度解析建议一键记入直接落库`() = runTest(dispatcher) {
        val api = mock<ApiService>()
        whenever(api.parseBill(any())).thenReturn(
            ParseResponse(
                amount = "35", categoryName = "三餐", remark = "午餐",
                confidence = "HIGH", needsConfirmation = false, categorySource = "HISTORY"
            )
        )
        val vm = newVMWithApi(api)
        vm.onEvent(QuickAddEvent.NlpInput("午餐35元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        // 高置信度：保留一步落库通路（账户由默认账户兜底）
        assertEquals(1, repo.addedBills.size)
        assertEquals(3500L, repo.addedBills[0].amountMinor)
        assertEquals("三餐", repo.addedBills[0].categoryName)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
        assertNull(vm.state.value.suggestion)
    }

    @Test
    fun `需确认的解析建议只出卡片不落库点卡片后回填表单`() = runTest(dispatcher) {
        val api = mock<ApiService>()
        whenever(api.parseBill(any())).thenReturn(
            ParseResponse(
                amount = "35", categoryName = "三餐", remark = "午餐",
                confidence = "MEDIUM", needsConfirmation = true, categorySource = "RULE",
                explanation = "规则词「午餐」归类"
            )
        )
        val vm = newVMWithApi(api)
        vm.onEvent(QuickAddEvent.NlpInput("午餐三四十元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        // 只出建议卡：不落库、不回填、无一步确认效果
        assertEquals(0, repo.addedBills.size)
        val card = requireNotNull(vm.state.value.suggestion) { "应出现需确认建议卡" }
        assertTrue(card.needsConfirmation)
        assertEquals("三餐", card.categoryName)
        assertEquals("规则词「午餐」归类", card.explanation)
        assertEquals("", vm.state.value.amount)
        assertNull(vm.state.value.selectedCategory)
        assertTrue(runCatching { withTimeout(100) { vm.effects.first() } }.isFailure)

        // 点卡片（检查后记入）：回填表单，仍不落库，等用户手动确认
        vm.onEvent(QuickAddEvent.SuggestionClick)
        advanceUntilIdle()
        assertEquals(0, repo.addedBills.size)
        assertEquals("35", vm.state.value.amount)
        assertEquals("三餐", vm.state.value.selectedCategory?.name)
        assertTrue(vm.state.value.suggestion?.filled == true)
        assertTrue(runCatching { withTimeout(100) { vm.effects.first() } }.isFailure)
    }

    @Test
    fun `模糊金额建议展示区间与中值且修改金额后按新值落库`() = runTest(dispatcher) {
        val api = mock<ApiService>()
        whenever(api.parseBill(any())).thenReturn(
            ParseResponse(
                amount = "35", categoryName = "三餐", remark = "买了些",
                confidence = "MEDIUM", needsConfirmation = true, categorySource = "RULE",
                fuzzyAmount = true, amountMinMinor = 3000L, amountMaxMinor = 4000L,
                explanation = "模糊金额「三四十」（区间30~40元，按中值35元记）"
            )
        )
        val vm = newVMWithApi(api)
        vm.onEvent(QuickAddEvent.NlpInput("午餐三四十元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        // 区间与中值正确进入展示模型：amount 为中值（整数分），区间 3000~4000 分
        val card = requireNotNull(vm.state.value.suggestion) { "应出现模糊金额建议卡" }
        assertTrue(card.fuzzyAmount)
        assertEquals(3500L, card.amount)
        assertEquals(3000L, card.amountMinMinor)
        assertEquals(4000L, card.amountMaxMinor)
        assertEquals("模糊金额「三四十」（区间30~40元，按中值35元记）", card.explanation)

        // 检查后记入：回填中值 → 用户改成 38 → 手动确认，按新值落库
        vm.onEvent(QuickAddEvent.SuggestionClick)
        assertEquals("35", vm.state.value.amount)
        vm.onEvent(QuickAddEvent.Clear)
        vm.onEvent(QuickAddEvent.Digit("3"))
        vm.onEvent(QuickAddEvent.Digit("8"))
        vm.onEvent(QuickAddEvent.Confirm)
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(3800L, repo.addedBills[0].amountMinor)
    }

    @Test
    fun `高置信度但分类匹配不到时降级为检查后记入不落库`() = runTest(dispatcher) {
        val api = mock<ApiService>()
        whenever(api.parseBill(any())).thenReturn(
            ParseResponse(
                amount = "12", categoryName = "不存在的分类",
                confidence = "HIGH", needsConfirmation = false
            )
        )
        val vm = newVMWithApi(api)
        vm.onEvent(QuickAddEvent.NlpInput("买点东西12元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        // 分类解析不到：不再静默无响应，也不落库——降级为「检查后记入」卡片
        assertEquals(0, repo.addedBills.size)
        val card = requireNotNull(vm.state.value.suggestion) { "应降级为检查后记入卡片" }
        assertTrue(card.filled)
        assertEquals("12", vm.state.value.amount)
        assertNull(vm.state.value.selectedCategory)
    }

    @Test
    fun `历史智能建议一键记入直接落库并清除卡片`() = runTest(dispatcher) {
        val api = mock<ApiService>()
        whenever(api.getSuggestion()).thenReturn(
            mapOf("label" to "午餐时间", "categoryName" to "三餐", "amount" to "20")
        )
        val vm = newVMWithApi(api)
        vm.loadSuggestion()
        runCurrent() // 先跑到 5 秒自动消失定时器的挂起点，断言卡片模型

        val card = requireNotNull(vm.state.value.suggestion) { "应加载出智能建议卡" }
        // insights/suggest 来自用户自己的历史高频模式：高置信度 + 历史来源 + 无需确认
        assertFalse(card.needsConfirmation)
        assertEquals("午餐时间", card.label)
        assertEquals("三餐", card.categoryName)
        assertEquals(2000L, card.amount)

        vm.onEvent(QuickAddEvent.SuggestionClick)
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(2000L, repo.addedBills[0].amountMinor)
        assertEquals("三餐", repo.addedBills[0].categoryName)
        assertEquals(QuickAddEffect.FinalConfirmCompleted, vm.effects.first())
        assertNull(vm.state.value.suggestion)
    }

    @Test
    fun `建议卡默认5秒自动消失固定后不消失可关闭并重新显示`() = runTest(dispatcher) {
        val api = mock<ApiService>()
        whenever(api.getSuggestion()).thenReturn(
            mapOf("label" to "午餐时间", "categoryName" to "三餐", "amount" to "20")
        )
        val vm = newVMWithApi(api)

        // 默认：5 秒后自动消失，且保留「重新显示」入口
        vm.loadSuggestion()
        runCurrent()
        assertNotNull(vm.state.value.suggestion)
        advanceUntilIdle()
        assertNull(vm.state.value.suggestion)
        assertTrue(vm.state.value.suggestionRestorable)

        // 重新显示：卡片恢复，入口收起；恢复后的卡重新参与 5 秒自动消失
        vm.onEvent(QuickAddEvent.ShowSuggestion)
        assertNotNull(vm.state.value.suggestion)
        assertFalse(vm.state.value.suggestionRestorable)

        // 固定：自动消失不再生效
        vm.onEvent(QuickAddEvent.ToggleSuggestionPin)
        assertTrue(vm.state.value.suggestionPinned)
        advanceUntilIdle()
        assertNotNull(vm.state.value.suggestion)

        // 关闭：卡片清除、固定态复位，仍可重新显示
        vm.onEvent(QuickAddEvent.DismissSuggestion)
        assertNull(vm.state.value.suggestion)
        assertFalse(vm.state.value.suggestionPinned)
        assertTrue(vm.state.value.suggestionRestorable)
        vm.onEvent(QuickAddEvent.ShowSuggestion)
        assertNotNull(vm.state.value.suggestion)
        // 未固定 → 重新计时 5 秒后再次自动收起
        advanceUntilIdle()
        assertNull(vm.state.value.suggestion)
        assertTrue(vm.state.value.suggestionRestorable)
    }

    /** Minimal repository fake — no Room, drives state via MutableStateFlow. */
    private class FakeBillRepository : BillRepository {
        override val expenseCategories: MutableStateFlow<List<Category>> = MutableStateFlow(
            listOf(Category(1, "三餐", "meals", BillType.EXPENSE), Category(2, "交通", "transport", BillType.EXPENSE))
        )
        override val incomeCategories: MutableStateFlow<List<Category>> = MutableStateFlow(
            listOf(Category(11, "工资", "salary", BillType.INCOME))
        )
        override val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(
            listOf(Account(1, "微信", 0L, "#28C145"))
        )

        val addedBills = mutableListOf<Bill>()
        private var nextId = 100L

        override suspend fun addBill(bill: Bill): Long {
            addedBills += bill
            return ++nextId
        }

        override fun observeAllBills(): Flow<List<Bill>> = flowOf(emptyList())
        override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> =
            flowOf(emptyList())
        override fun observeTemplates(): Flow<List<BillTemplate>> = flowOf(emptyList())
        override fun observeBudgets(): Flow<List<Budget>> = flowOf(emptyList())
        override fun observeChatMessages(): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun insertChatMessage(message: ChatMessage): Long = 0
        override suspend fun countChatMessages(kind: String, since: Long): Long = 0
        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Long = 0L
        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Long = 0L
        override fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Long> = flowOf(0L)
        override fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Long> = flowOf(0L)
        override suspend fun updateBill(bill: Bill) {}
        override suspend fun deleteBill(bill: Bill) {}
        override suspend fun reorderBills(bills: List<Bill>) {}
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
        var allSubCategories: List<SubCategory> = emptyList()
        override suspend fun getAllSubCategories(): List<SubCategory> = allSubCategories
        override suspend fun getBudget(monthStart: Long): Budget? = null
        override suspend fun upsertBudget(budget: Budget) {}
        override suspend fun getUnsyncedBudgets(): List<Budget> = emptyList()
        override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun deleteBudgetByServerId(serverId: Long) {}
        override suspend fun clearLocalData() {}
        override suspend fun loadReferenceData() {}
        override suspend fun seedIfNeeded() {}
        override suspend fun getDailyReport(dayStart: Long, dayEnd: Long): DailyReport =
            DailyReport("", 0L, 0L, emptyList(), emptyList(), 0)
        override suspend fun countUnsynced(): Long = 0
        override suspend fun getAccountNet(accountId: Long): Long = 0L
        override suspend fun reconcileAccount(account: Account, openingOffset: Long): Account = account
        override suspend fun reconcileAllAccounts(): List<Account> = emptyList()
    }

    private class FakeAccountRepository : AccountRepository {
        // VM 的默认账户（selectedAccount）来自 accountRepository.observeAccounts()，
        // 必须与真实仓库一样返回非空账户列表，否则保存会因「未选账户」被静默拒绝。
        val accounts = MutableStateFlow(
            listOf(Account(1, "微信", 0L, "#28C145"))
        )

        override fun observeAccounts(): Flow<List<Account>> = accounts
        override suspend fun insertAccount(account: Account): Long = 0
        override suspend fun updateAccount(account: Account) {}
        override suspend fun updateAccountLocal(account: Account) {}
        override suspend fun softDeleteAccount(account: Account) {}
        override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun getUnsyncedAccounts(): List<Account> = emptyList()
        override suspend fun getAccountByServerId(serverId: Long): Account? = null
        override suspend fun deleteAccountByServerId(serverId: Long) {}
        override suspend fun getAccountNet(accountId: Long): Long = 0L
        override suspend fun reconcileAccount(account: Account, openingOffset: Long): Account = account
        override suspend fun reconcileAllAccounts(): List<Account> = emptyList()
    }
}
