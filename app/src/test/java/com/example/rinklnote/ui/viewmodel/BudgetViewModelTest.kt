package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.data.repository.BudgetRepository
import com.example.rinklnote.data.repository.DailyReport
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.util.getMonthStart
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 预算三层（总额/分类/子分类）派生逻辑（Task 6）。
 * 纯函数部分直接测 deriveMonthBudget / findBudgetRow；
 * ViewModel 部分用 fake repository 注入固定预算/账单列表验证合流与 SetBudget。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var billRepo: FakeBillRepository
    private lateinit var budgetRepo: FakeBudgetRepository

    private val monthStart = getMonthStart()
    private val prevMonthStart = getMonthStart(-1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        billRepo = FakeBillRepository()
        budgetRepo = FakeBudgetRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVM(): BudgetViewModel {
        val vm = BudgetViewModel(billRepo, budgetRepo, syncManager = null)
        advanceUntilIdle()
        return vm
    }

    private fun budgetRow(
        amount: Double,
        categoryId: Long? = null,
        subCategoryId: Long? = null,
        month: Long = monthStart
    ) = Budget(
        monthStart = month,
        amount = amount,
        periodType = "MONTHLY",
        categoryId = categoryId,
        subCategoryId = subCategoryId
    )

    private fun bill(
        id: Long,
        amount: Double,
        categoryId: Long,
        categoryName: String,
        subCategoryName: String? = null
    ) = Bill(
        id = id,
        amount = amount,
        billType = BillType.EXPENSE,
        categoryId = categoryId,
        categoryName = categoryName,
        subCategoryName = subCategoryName,
        accountId = 1,
        date = monthStart,
        createdAt = id
    )

    private fun expenseCategories() = listOf(
        Category(1, "三餐", "meals", BillType.EXPENSE),
        Category(2, "交通", "transport", BillType.EXPENSE)
    )

    private fun subCategories() = listOf(
        SubCategory(11, "午餐", 1),
        SubCategory(12, "晚餐", 1),
        SubCategory(21, "打车", 2),
        SubCategory(22, "地铁", 2)
    )

    // ---------- 纯函数：deriveMonthBudget ----------

    @Test
    fun `derive aggregates total category and sub category budgets together`() {
        val budgets = listOf(
            budgetRow(amount = 1000.0),
            budgetRow(amount = 300.0, categoryId = 1),
            budgetRow(amount = 100.0, categoryId = 2),
            budgetRow(amount = 80.0, categoryId = 1, subCategoryId = 11),
            budgetRow(amount = 60.0, categoryId = 2, subCategoryId = 21)
        )
        val bills = listOf(
            bill(1, 20.0, 1, "三餐", "午餐"),
            bill(2, 30.0, 2, "交通", "打车"),
            bill(3, 50.0, 1, "三餐", null)
        )

        val result = deriveMonthBudget(budgets, bills, expenseCategories(), subCategories(), monthStart)

        // 总额层
        assertEquals(1000.0, result.totalBudget?.amount ?: 0.0, 0.0001)
        assertEquals(100.0, result.monthExpense, 0.0001)
        val totalState = BudgetState(totalBudget = result.totalBudget, monthExpense = result.monthExpense)
        assertEquals(0.1f, totalState.totalProgress, 0.0001f)
        assertFalse(totalState.isOverTotal)

        // 分类层：三餐 20(午餐) + 50(无子分类) = 70
        val cat1 = result.categoryBudgets.first { it.categoryId == 1L }
        assertEquals("三餐", cat1.categoryName)
        assertEquals(300.0, cat1.amount, 0.0001)
        assertEquals(70.0, cat1.expense, 0.0001)

        // 交通 30(打车)
        val cat2 = result.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(30.0, cat2.expense, 0.0001)

        // 子分类层：名称匹配（parentCategoryId == categoryId && name == subCategoryName）
        val lunch = cat1.subBudgets.first { it.subCategoryId == 11L }
        assertEquals("午餐", lunch.name)
        assertEquals(80.0, lunch.amount, 0.0001)
        assertEquals(20.0, lunch.expense, 0.0001)
        val taxi = cat2.subBudgets.first { it.subCategoryId == 21L }
        assertEquals(60.0, taxi.amount, 0.0001)
        assertEquals(30.0, taxi.expense, 0.0001)
    }

    @Test
    fun `category row expense aggregates when sub budget not set`() {
        val budgets = listOf(
            budgetRow(amount = 200.0, categoryId = 1),
            budgetRow(amount = 100.0, categoryId = 2)
        )
        // 子分类预算未设，但账单带子分类名 → 支出全部归集到分类行，子分类列表为空
        val bills = listOf(
            bill(1, 20.0, 1, "三餐", "午餐"),
            bill(2, 30.0, 1, "三餐", "晚餐"),
            bill(3, 10.0, 2, "交通", "地铁")
        )

        val result = deriveMonthBudget(budgets, bills, expenseCategories(), subCategories(), monthStart)

        assertEquals(60.0, result.monthExpense, 0.0001)
        val cat1 = result.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(50.0, cat1.expense, 0.0001)
        assertTrue(cat1.subBudgets.isEmpty())
        val cat2 = result.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(10.0, cat2.expense, 0.0001)
    }

    @Test
    fun `sub budget matched by name under parent category`() {
        val budgets = listOf(
            budgetRow(amount = 200.0, categoryId = 1),
            budgetRow(amount = 80.0, categoryId = 1, subCategoryId = 11) // 午餐有预算，晚餐无
        )
        val bills = listOf(
            bill(1, 20.0, 1, "三餐", "午餐"),
            bill(2, 30.0, 1, "三餐", "晚餐")
        )

        val result = deriveMonthBudget(budgets, bills, expenseCategories(), subCategories(), monthStart)

        val lunch = result.categoryBudgets.first { it.categoryId == 1L }.subBudgets.first { it.subCategoryId == 11L }
        assertEquals("午餐", lunch.name)
        assertEquals(20.0, lunch.expense, 0.0001)
        assertFalse(lunch.isOverBudget)
    }

    @Test
    fun `categories with expense but no budget are completed with amount zero`() {
        val budgets = listOf(budgetRow(amount = 1000.0)) // 只设了总额
        val bills = listOf(
            bill(1, 20.0, 1, "三餐", "午餐"),
            bill(2, 30.0, 1, "三餐", "晚餐")
        )

        val result = deriveMonthBudget(budgets, bills, expenseCategories(), subCategories(), monthStart)

        val cat1 = result.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(0.0, cat1.amount, 0.0001)
        assertEquals(50.0, cat1.expense, 0.0001)
        // 交通无支出无预算 → 不补全
        assertEquals(1, result.categoryBudgets.size)
    }

    // ---------- ViewModel：合流 + SetBudget ----------

    @Test
    fun `vm three layers aggregate progress correctly`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(
            budgetRow(amount = 1000.0),
            budgetRow(amount = 300.0, categoryId = 1),
            budgetRow(amount = 80.0, categoryId = 1, subCategoryId = 11)
        )
        billRepo.bills.value = listOf(
            bill(1, 20.0, 1, "三餐", "午餐"),
            bill(2, 50.0, 1, "三餐", null),
            bill(3, 30.0, 2, "交通", "打车")
        )

        val vm = newVM()
        val state = vm.state.value

        assertEquals(1000.0, state.totalBudget?.amount ?: 0.0, 0.0001)
        assertEquals(100.0, state.monthExpense, 0.0001)
        assertEquals(0.1f, state.totalProgress, 0.0001f)

        val cat1 = state.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(70.0, cat1.expense, 0.0001)
        assertEquals(0.7f / 3f, cat1.progress, 0.0001f)
        val lunch = cat1.subBudgets.first { it.subCategoryId == 11L }
        assertEquals(20.0, lunch.expense, 0.0001)
        assertEquals(0.25f, lunch.progress, 0.0001f)

        // 交通：无分类预算但有支出 → 补全为「未设」行
        val cat2 = state.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(0.0, cat2.amount, 0.0001)
        assertEquals(30.0, cat2.expense, 0.0001)
    }

    @Test
    fun `total over budget boundary equality is not over and exceeding is over`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 100.0))
        billRepo.bills.value = listOf(bill(1, 100.0, 1, "三餐"))

        val vm = newVM()
        assertEquals(1.0f, vm.state.value.totalProgress, 0.0001f)
        assertFalse(vm.state.value.isOverTotal)

        billRepo.bills.value = listOf(bill(1, 100.0, 1, "三餐"), bill(2, 1.0, 2, "交通"))
        advanceUntilIdle()

        assertTrue(vm.state.value.isOverTotal)
        assertEquals(1.0, vm.state.value.overTotalBy, 0.0001)
    }

    @Test
    fun `category over budget boundary equality is not over and exceeding is over`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 100.0, categoryId = 1))
        billRepo.bills.value = listOf(bill(1, 100.0, 1, "三餐"))

        val vm = newVM()
        val cat1 = vm.state.value.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(1.0f, cat1.progress, 0.0001f)
        assertFalse(cat1.isOverBudget)

        billRepo.bills.value = listOf(bill(1, 100.0, 1, "三餐"), bill(2, 1.0, 1, "三餐"))
        advanceUntilIdle()

        val over = vm.state.value.categoryBudgets.first { it.categoryId == 1L }
        assertTrue(over.isOverBudget)
        assertEquals(1.0, over.overBudgetBy, 0.0001)
    }

    @Test
    fun `setBudget category id upserts and appears as category row`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        val vm = newVM()

        vm.onEvent(BudgetEvent.SetBudget(amount = 300.0, categoryId = 1))
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        val saved = budgetRepo.upserted[0]
        assertEquals(monthStart, saved.monthStart)
        assertEquals(300.0, saved.amount, 0.0001)
        assertEquals(1L, saved.categoryId)
        assertNull(saved.subCategoryId)
        assertEquals("MONTHLY", saved.periodType)
        assertTrue(saved.dirty)

        val row = vm.state.value.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(300.0, row.amount, 0.0001)
        assertEquals("三餐", row.categoryName)
    }

    @Test
    fun `setBudget sub category id creates sub row under parent category`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        val vm = newVM()

        vm.onEvent(BudgetEvent.SetBudget(amount = 60.0, categoryId = 2, subCategoryId = 21))
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        assertEquals(21L, budgetRepo.upserted[0].subCategoryId)
        val row = vm.state.value.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(60.0, row.subBudgets.first { it.subCategoryId == 21L }.amount, 0.0001)
        assertEquals("打车", row.subBudgets.first { it.subCategoryId == 21L }.name)
    }

    @Test
    fun `setBudget reuses existing row for same dimension`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 100.0, categoryId = 1))
        val vm = newVM()

        vm.onEvent(BudgetEvent.SetBudget(amount = 150.0, categoryId = 1))
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        assertEquals(150.0, budgetRepo.upserted[0].amount, 0.0001)
        assertTrue(budgetRepo.upserted[0].dirty)
        assertEquals(1, budgetRepo.budgets.value.size)
        assertEquals(150.0, vm.state.value.categoryBudgets.first { it.categoryId == 1L }.amount, 0.0001)
    }

    @Test
    fun `last month surplus derives from previous month total budget minus expense`() = runTest(dispatcher) {
        billRepo.lastMonthExpense = 300.0
        budgetRepo.budgets.value = listOf(
            Budget(monthStart = prevMonthStart, amount = 500.0, periodType = "MONTHLY")
        )

        val vm = newVM()
        assertEquals(200.0, vm.state.value.lastMonthSurplus ?: 0.0, 0.0001)
    }

    @Test
    fun `last month surplus null when previous month has no budget`() = runTest(dispatcher) {
        billRepo.lastMonthExpense = 300.0

        val vm = newVM()
        assertNull(vm.state.value.lastMonthSurplus)
    }

    // ---------- fakes ----------

    private class FakeBillRepository : BillRepository {
        override val expenseCategories = MutableStateFlow<List<Category>>(emptyList())
        override val incomeCategories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts: Flow<List<Account>> = flowOf(emptyList())

        val bills = MutableStateFlow<List<Bill>>(emptyList())
        var lastMonthExpense: Double = 0.0

        private val subCategoriesByParent = mapOf(
            1L to listOf(SubCategory(11, "午餐", 1), SubCategory(12, "晚餐", 1)),
            2L to listOf(SubCategory(21, "打车", 2), SubCategory(22, "地铁", 2))
        )

        override fun observeAllBills(): Flow<List<Bill>> = bills
        override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> = bills
        override fun observeTemplates(): Flow<List<BillTemplate>> = flowOf(emptyList())
        override fun observeBudgets(): Flow<List<Budget>> = flowOf(emptyList())
        override fun observeChatMessages(): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun insertChatMessage(message: ChatMessage): Long = 0
        override suspend fun countChatMessages(kind: String, since: Long): Long = 0
        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double = lastMonthExpense
        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double = 0.0
        override suspend fun addBill(bill: Bill): Long = 0
        override suspend fun updateBill(bill: Bill) {}
        override suspend fun deleteBill(bill: Bill) {}
        override fun observeAccounts(): Flow<List<Account>> = accounts
        override suspend fun insertAccount(account: Account): Long = 0
        override suspend fun updateAccount(account: Account) {}
        override suspend fun updateAccountLocal(account: Account) {}
        override suspend fun softDeleteAccount(account: Account) {}
        override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun getUnsyncedAccounts(): List<Account> = emptyList()
        override suspend fun getAccountByServerId(serverId: Long): Account? = null
        override suspend fun deleteAccountByServerId(serverId: Long) {}
        override suspend fun getSubCategories(parentId: Long): List<SubCategory> =
            subCategoriesByParent[parentId] ?: emptyList()

        // 预算方法已迁至 BudgetRepository，此处保持旧接口兼容的空实现。
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

    private class FakeBudgetRepository : BudgetRepository {
        val budgets = MutableStateFlow<List<Budget>>(emptyList())
        val upserted = mutableListOf<Budget>()

        override fun observeBudgets(): Flow<List<Budget>> = budgets

        override suspend fun getBudget(monthStart: Long): Budget? = budgets.value.firstOrNull {
            it.monthStart == monthStart && it.categoryId == null && it.subCategoryId == null
        }

        override suspend fun upsertBudget(budget: Budget) {
            upserted += budget
            val list = budgets.value.toMutableList()
            val index = list.indexOfFirst {
                it.monthStart == budget.monthStart &&
                    it.categoryId == budget.categoryId &&
                    it.subCategoryId == budget.subCategoryId
            }
            if (index >= 0) list[index] = budget else list += budget
            budgets.value = list
        }

        override suspend fun getUnsyncedBudgets(): List<Budget> = emptyList()
        override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun deleteBudgetByServerId(serverId: Long) {}
    }
}
