package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.data.repository.BudgetRepository
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一级分类预算行；amount == 0 表示该分类未设预算（UI 显示「未设」）。 */
@androidx.compose.runtime.Immutable
data class CategoryBudgetState(
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,
    val expense: Double,
    val subBudgets: List<SubCategoryBudgetState> = emptyList()
) {
    val progress: Float
        get() = if (amount > 0) (expense / amount).toFloat().coerceIn(0f, 1f) else 0f

    val isOverBudget: Boolean
        get() = amount > 0 && expense > amount

    /** 超支金额，未超支时为 0。 */
    val overBudgetBy: Double
        get() = (expense - amount).coerceAtLeast(0.0)
}

/** 子分类预算行；amount == 0 表示该子分类未设预算。 */
@androidx.compose.runtime.Immutable
data class SubCategoryBudgetState(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amount: Double,
    val expense: Double
) {
    val progress: Float
        get() = if (amount > 0) (expense / amount).toFloat().coerceIn(0f, 1f) else 0f

    val isOverBudget: Boolean
        get() = amount > 0 && expense > amount

    /** 超支金额，未超支时为 0。 */
    val overBudgetBy: Double
        get() = (expense - amount).coerceAtLeast(0.0)
}

/**
 * 预算页分层状态：总额预算（totalBudget）独立，分类/子分类逐层派生。
 * 一应进度/超支/剩余天数判定均由「金额 / 支出」派生；预算未设（null 或金额 0）按未超支处理。
 */
@androidx.compose.runtime.Immutable
data class BudgetState(
    val totalBudget: Budget? = null,
    val monthExpense: Double = 0.0,
    val categoryBudgets: List<CategoryBudgetState> = emptyList(),
    val lastMonthSurplus: Double? = null,
    val monthStart: Long = getMonthStart(),
    val isLoading: Boolean = false
) {
    val totalProgress: Float
        get() = if (totalBudget != null && totalBudget.amount > 0) (monthExpense / totalBudget.amount).toFloat().coerceIn(0f, 1f) else 0f

    val isOverTotal: Boolean
        get() = totalBudget != null && totalBudget.amount > 0 && monthExpense > totalBudget.amount

    /** 总额超支金额，未超支时为 0。 */
    val overTotalBy: Double
        get() = (monthExpense - (totalBudget?.amount ?: 0.0)).coerceAtLeast(0.0)

    /** 本月是否设过任意一层预算（总额/分类/子分类），供空态引导判断。 */
    val hasAnyBudget: Boolean
        get() = totalBudget != null ||
            categoryBudgets.any { it.amount > 0 || it.subBudgets.any { sub -> sub.amount > 0 } }

    // 本月剩余天数 = 当月总天数 - 今天已过天数 + 1（含今天），仍在计划页顶部展示。
    val remainingDays: Int
        get() {
            val today = LocalDate.now(bookkeepingZone())
            return today.lengthOfMonth() - today.dayOfMonth + 1
        }
}

sealed interface BudgetEvent {
    /** categoryId / subCategoryId 均为 null = 设总额预算；仅 categoryId = 设分类预算；两者都在 = 设子分类预算。 */
    data class SetBudget(
        val amount: Double,
        val categoryId: Long? = null,
        val subCategoryId: Long? = null
    ) : BudgetEvent
}

/** deriveMonthBudget 的纯派生结果。 */
internal data class MonthBudgetDerivation(
    val totalBudget: Budget?,
    val monthExpense: Double,
    val categoryBudgets: List<CategoryBudgetState>
)

/**
 * 纯函数：预算列表 + 本月账单 + 支出分类/子分类参考数据 → 分层派生结果（独立可测）。
 *
 * 归集口径：
 * - 总行：periodType == "MONTHLY" && monthStart == 本月 && categoryId == null && subCategoryId == null
 * - 分类行：categoryId != null && subCategoryId == null
 * - 子分类行：subCategoryId != null
 * - 支出只计 BillType.EXPENSE：按 bill.categoryId 求和 → 分类行 expense；
 *   按 (categoryId, subCategoryName) 求和 → 子分类行 expense
 * - 子分类预算按 parentCategoryId == categoryId && name == subCategoryName 名称匹配
 * - 未设分类预算但本月有支出或该分类下有子分类预算的分类，补全为分类行（amount = 0，UI 显示「未设」）
 */
internal fun deriveMonthBudget(
    budgets: List<Budget>,
    bills: List<Bill>,
    expenseCategories: List<Category>,
    subCategories: List<SubCategory>,
    monthStart: Long
): MonthBudgetDerivation {
    val monthly = budgets.filter {
        it.periodType == "MONTHLY" && !it.deleted && it.monthStart == monthStart
    }
    val total = monthly.firstOrNull { it.categoryId == null && it.subCategoryId == null }
    val categoryRows = monthly.filter { it.categoryId != null && it.subCategoryId == null }
    val subRows = monthly.filter { it.subCategoryId != null }

    val expenseBills = bills.filter { it.billType == BillType.EXPENSE }
    val monthExpense = expenseBills.sumOf { it.amount }
    val categoryExpense = expenseBills
        .groupBy { it.categoryId }
        .mapValues { (_, list) -> list.sumOf { it.amount } }
    val subCategoryExpense = expenseBills
        .filter { !it.subCategoryName.isNullOrBlank() }
        .groupBy { it.categoryId to it.subCategoryName!! }
        .mapValues { (_, list) -> list.sumOf { it.amount } }

    val categoryNameById = expenseCategories.associateBy { it.id }
    val subCategoryNameById = subCategories.associateBy { it.id }

    val subStatesByParent: Map<Long, List<SubCategoryBudgetState>> = subRows
        .groupBy { it.categoryId ?: 0L }
        .mapValues { (parentId, rows) ->
            rows.map { row ->
                val name = subCategoryNameById[row.subCategoryId!!]?.name ?: ""
                SubCategoryBudgetState(
                    subCategoryId = row.subCategoryId!!,
                    name = name,
                    parentCategoryId = parentId,
                    amount = row.amount,
                    expense = subCategoryExpense[parentId to name] ?: 0.0
                )
            }
        }

    val budgetedCategories = categoryRows.map { row ->
        val categoryId = row.categoryId!!
        CategoryBudgetState(
            categoryId = categoryId,
            categoryName = categoryNameById[categoryId]?.name ?: "",
            amount = row.amount,
            expense = categoryExpense[categoryId] ?: 0.0,
            subBudgets = subStatesByParent[categoryId] ?: emptyList()
        )
    }

    // 补全：未设分类预算但本月有支出（或该分类下有子分类预算）的分类也展示为分类行。
    val covered = budgetedCategories.map { it.categoryId }.toSet()
    val completedCategories = expenseCategories
        .filter { it.id !in covered }
        .filter { categoryExpense.containsKey(it.id) || subStatesByParent.containsKey(it.id) }
        .map { category ->
            CategoryBudgetState(
                categoryId = category.id,
                categoryName = category.name,
                amount = 0.0,
                expense = categoryExpense[category.id] ?: 0.0,
                subBudgets = subStatesByParent[category.id] ?: emptyList()
            )
        }

    return MonthBudgetDerivation(
        totalBudget = total,
        monthExpense = monthExpense,
        categoryBudgets = budgetedCategories + completedCategories
    )
}


/**
 * 预算页 ViewModel：预算列表 + 本月账单双实时流合流，派生分层状态
 * （总额/分类/子分类进度与超支判定、上月结余、剩余天数）。设置预算后本地落库并尽力推服务端。
 */
class BudgetViewModel(
    private val repository: BillRepository,
    private val budgetRepository: BudgetRepository,
    private val syncManager: SyncManager? = null
) : ViewModel() {

    private val _state = MutableStateFlow(BudgetState())
    val state: StateFlow<BudgetState> = _state.asStateFlow()

    /** 子分类参考数据（seed 后基本不变），供子分类预算按名称匹配账单。 */
    private val _subCategories = MutableStateFlow<List<SubCategory>>(emptyList())


    init {
        val monthStart = getMonthStart()
        val nextMonthStart = getNextMonthStart()

        // 上月结余（M1：本地推导，本月启动时查一次；后续可切 summary 接口）：
        // 上月总额预算（预算列表中的 total 行，避免多行月份取到任意行）− 上月总支出。
        // 仅展示不结转，不参与本月任何派生。
        viewModelScope.launch {
            val prevStart = getMonthStart(-1)
            val curStart = getMonthStart()
            val lastMonthExpense = repository.getTotalExpense(prevStart, curStart)
            val lastTotalBudget = budgetRepository.observeBudgets()
                .first()
                .firstOrNull {
                    it.monthStart == prevStart && it.periodType == "MONTHLY" &&
                        !it.deleted && it.categoryId == null && it.subCategoryId == null
                }
            _state.update {
                it.copy(
                    lastMonthSurplus = if (lastTotalBudget != null) {
                        lastTotalBudget.amount - lastMonthExpense
                    } else {
                        null
                    }
                )
            }
        }

        // 子分类参考数据：随支出分类列表就绪后加载（分类 StateFlow 每次变更都会重载）。
        viewModelScope.launch {
            repository.expenseCategories.collect { categories ->
                _subCategories.value = categories.flatMap { category ->
                    repository.getSubCategories(category.id)
                }
            }
        }

        // 主合流：预算列表 + 本月账单 + 支出分类 + 子分类 → 分层派生状态。
        viewModelScope.launch {
            combine(
                budgetRepository.observeBudgets(),
                repository.observeBillsByMonth(monthStart, nextMonthStart),
                repository.expenseCategories,
                _subCategories
            ) { budgets, bills, categories, subCategories ->
                deriveMonthBudget(budgets, bills, categories, subCategories, monthStart)
            }.collect { derivation ->
                _state.update {
                    it.copy(
                        totalBudget = derivation.totalBudget,
                        monthExpense = derivation.monthExpense,
                        categoryBudgets = derivation.categoryBudgets,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onEvent(event: BudgetEvent) {
        when (event) {
            is BudgetEvent.SetBudget -> setBudget(event.amount, event.categoryId, event.subCategoryId)
        }
    }

    private fun setBudget(amount: Double, categoryId: Long?, subCategoryId: Long?) {
        val monthStart = getMonthStart()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // 按 (monthStart, categoryId, subCategoryId) 维度定位现有行：有则更新，无则新建。
            val existing = budgetRepository.findBudgetByScope(monthStart, categoryId, subCategoryId)
            val updated = existing
                ?.copy(amount = amount, updatedAt = now, dirty = true)
                ?: Budget(
                    monthStart = monthStart,
                    amount = amount,
                    periodType = "MONTHLY",
                    categoryId = categoryId,
                    subCategoryId = subCategoryId,
                    updatedAt = now,
                    dirty = true
                )
            budgetRepository.upsertBudget(updated)
            syncManager?.let { launch { it.pushBudget(updated) } }
        }
    }

    class Factory(
        private val repository: BillRepository,
        private val budgetRepository: BudgetRepository,
        private val syncManager: SyncManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BudgetViewModel(repository, budgetRepository, syncManager) as T
        }
    }
}
