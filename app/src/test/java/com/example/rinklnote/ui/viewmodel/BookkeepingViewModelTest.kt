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
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * BookkeepingViewModel month-window logic: the main bill list, chart source and
 * totals all follow selectedMonthOffset. Proves the window (not fixed "today-10d")
 * is what drives observeBillsByMonth / getTotalExpense / getTotalIncome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookkeepingViewModelTest {

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

    private fun TestScope.newVM(): BookkeepingViewModel {
        val vm = BookkeepingViewModel(repo, accountRepo, syncManager = null)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `initial window is the current month`() = runTest(dispatcher) {
        val vm = newVM()
        assertEquals(listOf(getMonthStart(0) to getNextMonthStart(0)), repo.billRanges)
    }

    @Test
    fun `selecting previous month switches window and totals`() = runTest(dispatcher) {
        val vm = newVM()
        vm.selectMonth(-1)
        advanceUntilIdle()
        assertEquals(getMonthStart(-1) to getNextMonthStart(-1), repo.billRanges.last())
        assertEquals(getMonthStart(-1) to getNextMonthStart(-1), repo.totalRanges.last())
        assertEquals(-1, vm.monthState.value.selectedMonthOffset)
    }

    @Test
    fun `same month select is a no-op`() = runTest(dispatcher) {
        val vm = newVM()
        val before = repo.billRanges.size
        vm.selectMonth(0)
        advanceUntilIdle()
        assertEquals(before, repo.billRanges.size)
    }

    /** Repository fake that records the month windows it is asked to observe/aggregate. */
    private class FakeBillRepository : BillRepository {
        override val expenseCategories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
        override val incomeCategories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
        override val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(emptyList())

        val billRanges = mutableListOf<Pair<Long, Long>>()
        val totalRanges = mutableListOf<Pair<Long, Long>>()

        override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> {
            billRanges += monthStart to nextMonthStart
            return flowOf(emptyList())
        }

        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double {
            totalRanges += monthStart to nextMonthStart
            return 0.0
        }

        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double {
            totalRanges += monthStart to nextMonthStart
            return 0.0
        }

        override fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Double> {
            totalRanges += monthStart to nextMonthStart
            return flowOf(0.0)
        }

        override fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Double> {
            totalRanges += monthStart to nextMonthStart
            return flowOf(0.0)
        }

        override fun observeAllBills(): Flow<List<Bill>> = flowOf(emptyList())
        override fun observeTemplates(): Flow<List<BillTemplate>> = flowOf(emptyList())
        override fun observeBudgets(): Flow<List<Budget>> = flowOf(emptyList())
        override fun observeChatMessages(): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun insertChatMessage(message: ChatMessage): Long = 0
        override suspend fun countChatMessages(kind: String, since: Long): Long = 0
        override suspend fun addBill(bill: Bill): Long = 1
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
