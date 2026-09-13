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
import com.example.rinklnote.util.Money
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 预算三层（总额/分类/子分类）派生逻辑（Task 6）。
 * 纯函数部分直接测 deriveMonthBudget；
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
        amountMinor = Money.yuanToMinor(amount),
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
        amountMinor = Money.yuanToMinor(amount),
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
        assertEquals(100000L, result.totalBudget?.amountMinor ?: 0L)
        assertEquals(10000L, result.monthExpenseMinor)
        val totalState = BudgetState(totalBudget = result.totalBudget, monthExpenseMinor = result.monthExpenseMinor)
        assertEquals(0.1f, totalState.totalProgress, 0.0001f)
        assertFalse(totalState.isOverTotal)

        // 分类层：三餐 20(午餐) + 50(无子分类) = 70
        val cat1 = result.categoryBudgets.first { it.categoryId == 1L }
        assertEquals("三餐", cat1.categoryName)
        assertEquals(30000L, cat1.amountMinor)
        assertEquals(7000L, cat1.expenseMinor)

        // 交通 30(打车)
        val cat2 = result.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(3000L, cat2.expenseMinor)

        // 子分类层：名称匹配（parentCategoryId == categoryId && name == subCategoryName）
        val lunch = cat1.subBudgets.first { it.subCategoryId == 11L }
        assertEquals("午餐", lunch.name)
        assertEquals(8000L, lunch.amountMinor)
        assertEquals(2000L, lunch.expenseMinor)
        val taxi = cat2.subBudgets.first { it.subCategoryId == 21L }
        assertEquals(6000L, taxi.amountMinor)
        assertEquals(3000L, taxi.expenseMinor)
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

        assertEquals(6000L, result.monthExpenseMinor)
        val cat1 = result.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(5000L, cat1.expenseMinor)
        assertTrue(cat1.subBudgets.isEmpty())
        val cat2 = result.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(1000L, cat2.expenseMinor)
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
        assertEquals(2000L, lunch.expenseMinor)
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
        assertEquals(0L, cat1.amountMinor)
        assertEquals(5000L, cat1.expenseMinor)
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

        assertEquals(100000L, state.totalBudget?.amountMinor ?: 0L)
        assertEquals(10000L, state.monthExpenseMinor)
        assertEquals(0.1f, state.totalProgress, 0.0001f)

        val cat1 = state.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(7000L, cat1.expenseMinor)
        assertEquals(0.7f / 3f, cat1.progress, 0.0001f)
        val lunch = cat1.subBudgets.first { it.subCategoryId == 11L }
        assertEquals(2000L, lunch.expenseMinor)
        assertEquals(0.25f, lunch.progress, 0.0001f)

        // 交通：无分类预算但有支出 → 补全为「未设」行
        val cat2 = state.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(0L, cat2.amountMinor)
        assertEquals(3000L, cat2.expenseMinor)
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
        assertEquals(100L, vm.state.value.overTotalBy)
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
        assertEquals(100L, over.overBudgetBy)
    }

    @Test
    fun `setBudget category id upserts and appears as category row`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        val vm = newVM()

        vm.onEvent(BudgetEvent.SetBudget(amountMinor = 30000L, categoryId = 1))
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        val saved = budgetRepo.upserted[0]
        assertEquals(monthStart, saved.monthStart)
        assertEquals(30000L, saved.amountMinor)
        assertEquals(1L, saved.categoryId)
        assertNull(saved.subCategoryId)
        assertEquals("MONTHLY", saved.periodType)
        assertTrue(saved.dirty)

        val row = vm.state.value.categoryBudgets.first { it.categoryId == 1L }
        assertEquals(30000L, row.amountMinor)
        assertEquals("三餐", row.categoryName)
    }

    @Test
    fun `setBudget sub category id creates sub row under parent category`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        val vm = newVM()

        vm.onEvent(BudgetEvent.SetBudget(amountMinor = 6000L, categoryId = 2, subCategoryId = 21))
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        assertEquals(21L, budgetRepo.upserted[0].subCategoryId)
        val row = vm.state.value.categoryBudgets.first { it.categoryId == 2L }
        assertEquals(6000L, row.subBudgets.first { it.subCategoryId == 21L }.amountMinor)
        assertEquals("打车", row.subBudgets.first { it.subCategoryId == 21L }.name)
    }

    @Test
    fun `setBudget reuses existing row for same dimension`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 100.0, categoryId = 1))
        val vm = newVM()

        vm.onEvent(BudgetEvent.SetBudget(amountMinor = 15000L, categoryId = 1))
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        assertEquals(15000L, budgetRepo.upserted[0].amountMinor)
        assertTrue(budgetRepo.upserted[0].dirty)
        assertEquals(1, budgetRepo.budgets.value.size)
        assertEquals(15000L, vm.state.value.categoryBudgets.first { it.categoryId == 1L }.amountMinor)
    }

    @Test
    fun `last month surplus derives from previous month total budget minus expense`() = runTest(dispatcher) {
        billRepo.lastMonthExpense = 30000L
        budgetRepo.budgets.value = listOf(
            Budget(monthStart = prevMonthStart, amountMinor = 50000L, periodType = "MONTHLY")
        )

        val vm = newVM()
        assertEquals(20000L, vm.state.value.lastMonthSurplusMinor ?: 0L)
    }

    @Test
    fun `last month surplus null when previous month has no budget`() = runTest(dispatcher) {
        billRepo.lastMonthExpense = 30000L

        val vm = newVM()
        assertNull(vm.state.value.lastMonthSurplusMinor)
    }

    // ---------- ViewModel：编辑页状态（EditBudget / CancelEdit / DeleteBudget） ----------

    @Test
    fun `editBudget fills edit state for total dimension`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 1000.0))
        billRepo.bills.value = listOf(bill(1, 100.0, 1, "三餐"))
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Total))

        val edit = vm.editState.value
        assertTrue(edit.target is BudgetEditTarget.Total)
        assertEquals(100000L, edit.existingAmountMinor)
        assertEquals(10000L, edit.monthExpenseMinor)
        // 剩余天数所有维度都提供（编辑页「日均可花」提示通用）
        assertTrue(edit.remainingDays != null)
    }

    @Test
    fun `editBudget fills edit state for category dimension`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 300.0, categoryId = 1))
        billRepo.bills.value = listOf(bill(1, 20.0, 1, "三餐"))
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Category(1, "三餐")))

        val edit = vm.editState.value
        assertEquals(BudgetEditTarget.Category(1, "三餐"), edit.target)
        assertEquals(30000L, edit.existingAmountMinor)
        assertEquals(2000L, edit.monthExpenseMinor)
        assertNotNull(edit.remainingDays)
    }

    @Test
    fun `editBudget fills edit state for sub category dimension`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 80.0, categoryId = 1, subCategoryId = 11))
        billRepo.bills.value = listOf(bill(1, 20.0, 1, "三餐", "午餐"))
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.SubCategory(11, "午餐", 1, "三餐")))

        val edit = vm.editState.value
        assertEquals(BudgetEditTarget.SubCategory(11, "午餐", 1, "三餐"), edit.target)
        assertEquals(8000L, edit.existingAmountMinor)
        assertEquals(2000L, edit.monthExpenseMinor)
    }

    @Test
    fun `editBudget treats zero amount category as unset`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        // 只设总额；三餐有支出 → 补全为 amountMinor = 0 的「未设」行
        budgetRepo.budgets.value = listOf(budgetRow(amount = 1000.0))
        billRepo.bills.value = listOf(bill(1, 50.0, 1, "三餐"))
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Category(1, "三餐")))

        val edit = vm.editState.value
        assertNull(edit.existingAmountMinor)
        assertEquals(5000L, edit.monthExpenseMinor)
    }

    @Test
    fun `cancelEdit clears edit state`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 300.0, categoryId = 1))
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Category(1, "三餐")))
        vm.onEvent(BudgetEvent.CancelEdit)

        assertNull(vm.editState.value.target)
    }

    @Test
    fun `deleteBudget soft deletes existing row and marks dirty`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(budgetRow(amount = 300.0, categoryId = 1))
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Category(1, "三餐")))
        vm.onEvent(BudgetEvent.DeleteBudget)
        advanceUntilIdle()

        assertEquals(1, budgetRepo.upserted.size)
        val tombstone = budgetRepo.upserted[0]
        assertEquals(30000L, tombstone.amountMinor)
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.dirty)
        assertTrue(tombstone.updatedAt != null)
        // 软删后派生状态不再包含该行（本月无其支出，也不会被补全）
        assertTrue(vm.state.value.categoryBudgets.none { it.categoryId == 1L })
    }

    @Test
    fun `deleteBudget is a no-op when dimension has no budget row`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        val vm = newVM()

        vm.onEvent(BudgetEvent.EditBudget(BudgetEditTarget.Category(1, "三餐")))
        vm.onEvent(BudgetEvent.DeleteBudget)
        advanceUntilIdle()

        assertTrue(budgetRepo.upserted.isEmpty())
    }

    // ---------- 编辑页分析派生（deriveEditState） ----------

    @Test
    fun `derive passes month bills through for edit analytics`() {
        val bills = listOf(bill(1, 20.0, 1, "三餐"))
        val result = deriveMonthBudget(emptyList(), bills, expenseCategories(), subCategories(), monthStart)
        assertEquals(bills, result.monthBills)
    }

    @Test
    fun `deriveEditState filters bills by category dimension and builds trend`() = runTest(dispatcher) {
        billRepo.expenseCategories.value = expenseCategories()
        budgetRepo.budgets.value = listOf(
            budgetRow(amount = 1000.0),
            budgetRow(amount = 300.0, categoryId = 1)
        )
        billRepo.bills.value = listOf(
            bill(1, 20.0, 1, "三餐", "午餐"),
            bill(2, 30.0, 1, "三餐", "午餐"),
            bill(3, 10.0, 2, "交通")
        )
        val vm = newVM()

        val edit = deriveEditState(BudgetEditTarget.Category(1, "三餐"), vm.state.value, prevMonthSamePeriodMinor = 4000L)

        assertEquals(30000L, edit.existingAmountMinor)
        assertEquals(5000L, edit.monthExpenseMinor)
        // 趋势只含该维度账单：午餐两笔 50 元落在月首日
        val day = java.time.Instant.ofEpochMilli(monthStart)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().dayOfMonth
        assertEquals(5000L, edit.dailyTrend.first { it.day == day }.amountMinor)
        assertEquals(edit.elapsedDays, edit.dailyTrend.size)
        // 构成：仅子分类「午餐」
        assertEquals(1, edit.subBreakdown.size)
        assertEquals("午餐", edit.subBreakdown[0].name)
        assertEquals(5000L, edit.subBreakdown[0].amountMinor)
        // 相关账单 2 笔，上月同期异步值透传
        assertEquals(2, edit.billCount)
        assertEquals(2, edit.recentBills.size)
        assertEquals(4000L, edit.prevMonthSamePeriodMinor)
        assertTrue(edit.remainingDays != null && edit.daysInMonth > 0)
    }

    @Test
    fun `deriveEditState total dimension groups breakdown by category and caps recent bills`() {
        val bills = (1L..15L).map { id -> bill(id, 1.0, 1, "三餐") }
        val state = BudgetState(
            totalBudget = budgetRow(amount = 1000.0),
            monthExpenseMinor = 1500L,
            monthBills = bills
        )

        val edit = deriveEditState(BudgetEditTarget.Total, state, prevMonthSamePeriodMinor = null)

        assertEquals(100000L, edit.existingAmountMinor)
        assertEquals(15, edit.billCount)
        assertEquals(10, edit.recentBills.size)
        // 最近在前：id 最大的排最前（同日按 id 倒序）
        assertEquals(15L, edit.recentBills.first().id)
        assertEquals(1, edit.subBreakdown.size)
        assertEquals("三餐", edit.subBreakdown[0].name)
        assertEquals(1500L, edit.subBreakdown[0].amountMinor)
    }

    @Test
    fun `deriveEditState sub dimension filters by category and sub name`() {
        val state = BudgetState(
            categoryBudgets = listOf(
                CategoryBudgetState(
                    categoryId = 1,
                    categoryName = "三餐",
                    amountMinor = 5000L,
                    expenseMinor = 5000L,
                    subBudgets = listOf(
                        SubCategoryBudgetState(subCategoryId = 11, name = "午餐", parentCategoryId = 1, amountMinor = 2000L, expenseMinor = 2000L)
                    )
                )
            ),
            monthBills = listOf(
                bill(1, 20.0, 1, "三餐", "午餐"),
                bill(2, 30.0, 1, "三餐", "晚餐"),
                bill(3, 10.0, 2, "交通", "午餐"),
                bill(4, 5.0, 1, "三餐", null)
            )
        )

        val edit = deriveEditState(BudgetEditTarget.SubCategory(11, "午餐", 1, "三餐"), state, prevMonthSamePeriodMinor = null)

        assertEquals(2000L, edit.existingAmountMinor)
        assertEquals(2000L, edit.monthExpenseMinor)
        // 只统计「三餐 · 午餐」的账单
        assertEquals(1, edit.billCount)
        assertEquals(1, edit.recentBills.size)
        assertEquals(2000L, edit.recentBills[0].amountMinor)
        // 子分类维度无构成卡
        assertTrue(edit.subBreakdown.isEmpty())
    }

    // ---------- fakes ----------

    private class FakeBillRepository : BillRepository {
        override val expenseCategories = MutableStateFlow<List<Category>>(emptyList())
        override val incomeCategories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts: Flow<List<Account>> = flowOf(emptyList())

        val bills = MutableStateFlow<List<Bill>>(emptyList())
        var lastMonthExpense: Long = 0L

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
        override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Long = lastMonthExpense
        override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Long = 0L
        override fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Long> = flowOf(0L)
        override fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Long> = flowOf(0L)
        override suspend fun addBill(bill: Bill): Long = 0
        override suspend fun updateBill(bill: Bill) {}
        override suspend fun deleteBill(bill: Bill) {}
        override suspend fun reorderBills(bills: List<Bill>) {}
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
        override suspend fun getAllSubCategories(): List<SubCategory> = emptyList()

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
            DailyReport("", 0L, 0L, emptyList(), emptyList(), 0)
        override suspend fun countUnsynced(): Long = 0
        override suspend fun getAccountNet(accountId: Long): Long = 0L
        override suspend fun reconcileAccount(account: Account, openingOffset: Long): Account = account
        override suspend fun reconcileAllAccounts(): List<Account> = emptyList()
    }

    private class FakeBudgetRepository : BudgetRepository {
        val budgets = MutableStateFlow<List<Budget>>(emptyList())
        val upserted = mutableListOf<Budget>()

        override fun observeBudgets(): Flow<List<Budget>> = budgets

        override suspend fun getBudget(monthStart: Long): Budget? = budgets.value.firstOrNull {
            it.monthStart == monthStart && it.categoryId == null && it.subCategoryId == null
        }

        override suspend fun findBudgetByScope(monthStart: Long, categoryId: Long?, subCategoryId: Long?): Budget? =
            budgets.value.firstOrNull {
                it.monthStart == monthStart && it.categoryId == categoryId && it.subCategoryId == subCategoryId
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
