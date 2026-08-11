package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.repository.BillRepository
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeBillRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Constructs the VM with a null syncManager/api and lets init collectors run. */
    private fun TestScope.newVM(): QuickAddViewModel {
        val vm = QuickAddViewModel(repo, syncManager = null, api = null)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `digit builds amount and enables confirm only for positive values`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("1"))
        vm.onEvent(QuickAddEvent.Digit("0"))
        assertEquals("10", vm.state.value.amount)
        assertTrue(vm.state.value.isConfirmEnabled)

        vm.onEvent(QuickAddEvent.Digit("0"))
        assertEquals("100", vm.state.value.amount)

        vm.onEvent(QuickAddEvent.Backspace)
        assertEquals("10", vm.state.value.amount)

        vm.onEvent(QuickAddEvent.Clear)
        assertEquals("", vm.state.value.amount)
        assertFalse(vm.state.value.isConfirmEnabled)

        vm.onEvent(QuickAddEvent.Digit("0"))
        assertFalse(vm.state.value.isConfirmEnabled) // zero is not a valid amount
    }

    @Test
    fun `confirm requires selected category and account`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        assertTrue(vm.state.value.confirmed)
        assertEquals(QuickAddEffect.ConfirmRequested, vm.effects.first())
    }

    @Test
    fun `confirm without account stays unconfirmed`() = runTest(dispatcher) {
        repo.accounts.value = emptyList()
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("20"))
        vm.onEvent(QuickAddEvent.Confirm)
        assertFalse(vm.state.value.confirmed)
    }

    @Test
    fun `confirm with missing amount is ignored`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Confirm)
        assertFalse(vm.state.value.confirmed)
    }

    @Test
    fun `nlp local parse fills amount and category and requests confirm`() = runTest(dispatcher) {
        val vm = newVM() // api = null → local VoiceParser fallback
        vm.onEvent(QuickAddEvent.NlpInput("午餐20元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("20", s.amount)
        assertEquals("三餐", s.selectedCategory?.name)
        assertEquals("", s.nlpInput)
        assertFalse(s.isParsing)
        assertEquals(QuickAddEffect.ConfirmRequested, vm.effects.first())
    }

    @Test
    fun `nlp local parse without a recognized category does not request confirm`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.NlpInput("普通消费30元"))
        vm.onEvent(QuickAddEvent.NlpSubmit)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("30", s.amount)
        // Unrecognized category → keeps the default pre-selected category, but no
        // ConfirmRequested is sent (NLP intent is only confirmed when a category matches).
        assertEquals("三餐", s.selectedCategory?.name)
        assertFalse(s.confirmed)
    }

    @Test
    fun `finalConfirm saves exactly one bill and releases the guard`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(QuickAddEvent.Digit("25"))

        vm.finalConfirm()
        vm.finalConfirm() // second call while guard is set must be ignored
        advanceUntilIdle()

        assertEquals(1, repo.addedBills.size)
        assertEquals(25.0, repo.addedBills[0].amount, 0.0001)
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
        val vm = newVM()
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
            listOf(Category(1, "三餐", "meals", "EXPENSE"), Category(2, "交通", "transport", "EXPENSE"))
        )
        override val incomeCategories: MutableStateFlow<List<Category>> = MutableStateFlow(
            listOf(Category(11, "工资", "salary", "INCOME"))
        )
        override val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(
            listOf(Account(1, "微信", 0.0, "#28C145"))
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
        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double = 0.0
        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double = 0.0
        override suspend fun updateBill(bill: Bill) {}
        override suspend fun deleteBill(bill: Bill) {}
        override suspend fun updateAccount(account: Account) {}
        override suspend fun getSubCategories(parentId: Long): List<SubCategory> = emptyList()
        override suspend fun getBudget(monthStart: Long): Budget? = null
        override suspend fun upsertBudget(budget: Budget) {}
        override suspend fun getUnsyncedBudgets(): List<Budget> = emptyList()
        override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun deleteBudgetByServerId(serverId: Long) {}
        override suspend fun clearLocalData() {}
        override suspend fun loadReferenceData() {}
        override suspend fun seedIfNeeded() {}
    }
}
