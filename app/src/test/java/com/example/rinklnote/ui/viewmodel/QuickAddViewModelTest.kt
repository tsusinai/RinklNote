package com.example.rinklnote.ui.viewmodel

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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
