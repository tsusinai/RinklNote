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
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.today
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
        // 合计已改为由账单流内存派生（性能优化：不再单独监听 total 流），故只校验账单窗口切换
        assertEquals(getMonthStart(-1) to getNextMonthStart(-1), repo.billRanges.last())
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

    @Test
    fun `reorder event stamps dirty and assigns descending ranks by new order`() = runTest(dispatcher) {
        val vm = newVM()
        val day = getMonthStart(0) + 1
        fun bill(id: Long) = Bill(
            id = id, amountMinor = 1000L, categoryId = 1, categoryName = "测试",
            accountId = 1, date = day, createdAt = id
        )
        // 新序：b3 在最上，b1 居中，b2 在最下
        vm.onEvent(BookkeepingEvent.ReorderBills(listOf(bill(3), bill(1), bill(2))))
        advanceUntilIdle()

        val reordered = repo.reordered.single()
        assertEquals(listOf(3L, 1L, 2L), reordered.map { it.id })
        // 盖章：全部置脏、更新时间非零
        assertTrue(reordered.all { it.dirty })
        assertTrue(reordered.all { (it.updatedAt ?: 0) > 0 })
        // 名次：自上而下严格降序且互异
        val ranks = reordered.map { it.sortOrder!! }
        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals(ranks.distinct().size, ranks.size)
    }

    /** 选择跨年月份后，账单、收支合计与图表均应来自同一业务时区窗口。 */
    @Test
    fun monthSelectionEventRefreshesAllMonthDataAcrossYears() = runTest(dispatcher) {
        val december = LocalDate.of(today().year - 1, 12, 1)
        val start = december.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
        val next = december.plusMonths(1).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
        val offset = ChronoUnit.MONTHS.between(today().withDayOfMonth(1), december).toInt()
        val expense = Bill(id = 1, amountMinor = 98765L, categoryId = 1, categoryName = "三餐",
            accountId = 1, date = december.plusDays(4).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli())
        val income = Bill(id = 2, amountMinor = 300000L, billType = BillType.INCOME,
            categoryId = 2, categoryName = "工资", accountId = 1,
            date = december.plusDays(8).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli())
        repo.monthBills[start] = MutableStateFlow(listOf(expense, income))
        val vm = newVM()

        vm.onEvent(BookkeepingEvent.SelectMonth(offset))
        advanceUntilIdle()

        val state = vm.monthState.value
        assertEquals(start to next, repo.billRanges.last())
        assertEquals(offset, state.selectedMonthOffset)
        assertEquals(listOf(expense, income), state.bills)
        assertEquals(98765L, state.totalExpense)
        assertEquals(300000L, state.totalIncome)
        assertEquals(mapOf(5 to 98765L), state.monthDetail.dayAmounts)
        assertEquals(98765L, state.monthDetail.pieSlices.single().amount)
        assertEquals(31, state.monthDetail.daySeries.size)
        assertEquals(98765f, state.monthDetail.daySeries[4])
    }

    /** 切换后取消旧月份订阅，旧窗口后续更新不能覆盖新月份数据。 */
    @Test
    fun previousMonthEmissionsCannotOverwriteSelectedMonth() = runTest(dispatcher) {
        val currentStart = getMonthStart(0)
        val previousStart = getMonthStart(-1)
        val previousBill = Bill(id = 1, amountMinor = 4200L, categoryId = 1, categoryName = "三餐",
            accountId = 1, date = previousStart)
        repo.monthBills[previousStart] = MutableStateFlow(listOf(previousBill))
        val vm = newVM()
        val oldFlow = requireNotNull(repo.monthBills[currentStart])
        vm.onEvent(BookkeepingEvent.SelectMonth(-1))
        advanceUntilIdle()

        oldFlow.value = listOf(previousBill.copy(id = 2, amountMinor = 999999L, date = currentStart))
        advanceUntilIdle()

        assertEquals(-1, vm.monthState.value.selectedMonthOffset)
        assertEquals(listOf(previousBill), vm.monthState.value.bills)
        assertEquals(4200L, vm.monthState.value.totalExpense)
        assertEquals(mapOf(1 to 4200L), vm.monthState.value.monthDetail.dayAmounts)
    }

    /** 空月份也要更新日历长度，不能沿用上一月的账单和序列。 */
    @Test
    fun emptyMonthSelectionClearsDataAndUsesActualDayCount() = runTest(dispatcher) {
        val february = LocalDate.of(today().year - 1, 2, 1)
        val offset = ChronoUnit.MONTHS.between(today().withDayOfMonth(1), february).toInt()
        val vm = newVM()
        vm.onEvent(BookkeepingEvent.SelectMonth(offset))
        advanceUntilIdle()

        val state = vm.monthState.value
        assertTrue(state.bills.isEmpty())
        assertEquals(0L, state.totalExpense)
        assertEquals(0L, state.totalIncome)
        assertTrue(state.monthDetail.pieSlices.isEmpty())
        assertEquals(february.lengthOfMonth(), state.monthDetail.daySeries.size)
        assertTrue(state.monthDetail.daySeries.all { it == 0f })
    }

    /** 仓库替身记录订阅窗口，按月维护可继续发射的账单流。 */
    private class FakeBillRepository : BillRepository {
        override val expenseCategories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
        override val incomeCategories: MutableStateFlow<List<Category>> = MutableStateFlow(emptyList())
        override val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(emptyList())

        val billRanges = mutableListOf<Pair<Long, Long>>()
        val totalRanges = mutableListOf<Pair<Long, Long>>()
        val reordered = mutableListOf<List<Bill>>()
        val monthBills = mutableMapOf<Long, MutableStateFlow<List<Bill>>>()

        override suspend fun reorderBills(bills: List<Bill>) {
            reordered += bills
        }

        override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> {
            billRanges += monthStart to nextMonthStart
            return monthBills.getOrPut(monthStart) { MutableStateFlow(emptyList()) }
        }

        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Long {
            totalRanges += monthStart to nextMonthStart
            return 0L
        }

        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Long {
            totalRanges += monthStart to nextMonthStart
            return 0L
        }

        override fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Long> {
            totalRanges += monthStart to nextMonthStart
            return flowOf(0L)
        }

        override fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Long> {
            totalRanges += monthStart to nextMonthStart
            return flowOf(0L)
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
        override suspend fun getAllSubCategories(): List<SubCategory> = emptyList()
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
        override fun observeAccounts(): Flow<List<Account>> = flowOf(emptyList())
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
