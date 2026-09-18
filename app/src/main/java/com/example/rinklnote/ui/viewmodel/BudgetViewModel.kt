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
import com.example.rinklnote.domain.BudgetBurnRisk
import com.example.rinklnote.domain.budgetBurnRisk
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 金额单位：分（minor unit）。预算与支出的求和 / 比较全程整数运算；
 * 仅「进度百分比」这一步把分子分母转 Double 相除，避免整数相除截断为 0。
 */

/** 一级分类预算行；amountMinor == 0 表示该分类未设预算（UI 显示「未设」）。 */
@androidx.compose.runtime.Immutable
data class CategoryBudgetState(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long,
    val expenseMinor: Long,
    val subBudgets: List<SubCategoryBudgetState> = emptyList()
) {
    val progress: Float
        get() = if (amountMinor > 0) (expenseMinor.toDouble() / amountMinor).toFloat().coerceIn(0f, 1f) else 0f

    val isOverBudget: Boolean
        get() = amountMinor > 0 && expenseMinor > amountMinor

    /** 超支金额（分），未超支时为 0。 */
    val overBudgetBy: Long
        get() = (expenseMinor - amountMinor).coerceAtLeast(0L)
}

/** 子分类预算行；amountMinor == 0 表示该子分类未设预算。 */
@androidx.compose.runtime.Immutable
data class SubCategoryBudgetState(
    val subCategoryId: Long,
    val name: String,
    val parentCategoryId: Long,
    val amountMinor: Long,
    val expenseMinor: Long
) {
    val progress: Float
        get() = if (amountMinor > 0) (expenseMinor.toDouble() / amountMinor).toFloat().coerceIn(0f, 1f) else 0f

    val isOverBudget: Boolean
        get() = amountMinor > 0 && expenseMinor > amountMinor

    /** 超支金额（分），未超支时为 0。 */
    val overBudgetBy: Long
        get() = (expenseMinor - amountMinor).coerceAtLeast(0L)
}

/**
 * 预算页分层状态：总额预算（totalBudget）独立，分类/子分类逐层派生。
 * 一应进度/超支/剩余天数判定均由「金额 / 支出」派生；预算未设（null 或金额 0）按未超支处理。
 */
@androidx.compose.runtime.Immutable
data class BudgetState(
    val totalBudget: Budget? = null,
    val monthExpenseMinor: Long = 0L,
    val categoryBudgets: List<CategoryBudgetState> = emptyList(),
    /** 全部支出分类；供计划页在下框直接选择尚无双层预算行的分类。 */
    val expenseCategories: List<Category> = emptyList(),
    val lastMonthSurplusMinor: Long? = null,
    /** 本月全部账单（编辑页分析按维度过滤派生）。 */
    val monthBills: List<Bill> = emptyList(),
    val monthStart: Long = getMonthStart(),
    val isLoading: Boolean = false
) {
    val totalProgress: Float
        get() = if (totalBudget != null && totalBudget.amountMinor > 0)
            (monthExpenseMinor.toDouble() / totalBudget.amountMinor).toFloat().coerceIn(0f, 1f) else 0f

    val isOverTotal: Boolean
        get() = totalBudget != null && totalBudget.amountMinor > 0 && monthExpenseMinor > totalBudget.amountMinor

    /** 总额超支金额（分），未超支时为 0。 */
    val overTotalBy: Long
        get() = (monthExpenseMinor - (totalBudget?.amountMinor ?: 0L)).coerceAtLeast(0L)

    /** 本月是否设过任意一层预算（总额/分类/子分类），供空态引导判断。 */
    val hasAnyBudget: Boolean
        get() = totalBudget != null ||
            categoryBudgets.any { it.amountMinor > 0 || it.subBudgets.any { sub -> sub.amountMinor > 0 } }

    /**
     * 分类已设预算映射（categoryId → 金额分），供「分类预算设置」页直接取用。
     * 金额 0 表示该分类未设预算（含仅有支出/子分类预算而被补全出的行）。
     */
    val categoryBudgetAmounts: Map<Long, Long>
        get() = categoryBudgets.associate { it.categoryId to it.amountMinor }

    // 本月剩余天数 = 当月总天数 - 今天已过天数 + 1（含今天），仍在计划页顶部展示。
    val remainingDays: Int
        get() {
            val today = LocalDate.now(bookkeepingZone())
            return today.lengthOfMonth() - today.dayOfMonth + 1
        }

    /**
     * 月总额预算燃烧风险（Task 4.1 预算-挑战联动，实时派生零存储）：
     * 按当前燃烧速度预测到月末 ÷ 预算定档（口径与 Web 端钉死一致）；未设预算 → null。
     */
    val totalBurnRisk: BudgetBurnRisk?
        get() {
            val budget = totalBudget ?: return null
            val today = LocalDate.now(bookkeepingZone())
            return budgetBurnRisk(monthExpenseMinor, budget.amountMinor, today.dayOfMonth, today.lengthOfMonth())
        }
}

/** 预算编辑目标：总额 / 一级分类 / 子分类（点击行时构造，独立编辑页按维度取数与落库）。 */
sealed interface BudgetEditTarget {
    data object Total : BudgetEditTarget
    data class Category(val categoryId: Long, val categoryName: String) : BudgetEditTarget
    data class SubCategory(
        val subCategoryId: Long,
        val subCategoryName: String,
        val parentCategoryId: Long,
        val parentCategoryName: String
    ) : BudgetEditTarget
}

/** 单日支出（分），日支出趋势图数据点。 */
@androidx.compose.runtime.Immutable
data class DailySpend(val day: Int, val amountMinor: Long)

/** 构成占比条目（总额维度=一级分类构成；分类维度=子分类构成）。 */
@androidx.compose.runtime.Immutable
data class SubBreakdown(val name: String, val amountMinor: Long)

/**
 * 预算编辑页状态（与月度派生状态 [BudgetState] 分离，避免预算 Flow 每次发射牵动编辑页重组）。
 * `existingAmountMinor == null` 表示该维度未设预算（新设流程，无删除入口）。
 * 分析字段全部由 [deriveEditState] 从当月账单整数分本地派生；prevMonthSamePeriodMinor 异步补齐。
 */
@androidx.compose.runtime.Immutable
data class BudgetEditState(
    val target: BudgetEditTarget? = null,
    val existingAmountMinor: Long? = null,
    val monthExpenseMinor: Long = 0L,
    /** 剩余天数（含今天）。 */
    val remainingDays: Int? = null,
    /** 本月已过天数（含今天，业务时区）。 */
    val elapsedDays: Int = 0,
    /** 本月总天数。 */
    val daysInMonth: Int = 0,
    /** 本月每日支出（1..elapsedDays，仅该维度相关账单）。 */
    val dailyTrend: List<DailySpend> = emptyList(),
    /** 上月同期已花（分）；`null` = 尚未加载完成。 */
    val prevMonthSamePeriodMinor: Long? = null,
    /** 构成占比（总额→一级分类；分类→子分类；子分类维度为空）。 */
    val subBreakdown: List<SubBreakdown> = emptyList(),
    /** 该维度本月相关账单（最近在前，最多 10 笔），点击可跳账单编辑。 */
    val recentBills: List<Bill> = emptyList(),
    val billCount: Int = 0
)

sealed interface BudgetEvent {
    /** categoryId / subCategoryId 均为 null = 设总额预算；仅 categoryId = 设分类预算；两者都在 = 设子分类预算。 */
    data class SetBudget(
        val amountMinor: Long,
        val categoryId: Long? = null,
        val subCategoryId: Long? = null
    ) : BudgetEvent

    /** 进入独立编辑页：按目标维度填充 [BudgetEditState]（同步派生，无异步间隙）。 */
    data class EditBudget(val target: BudgetEditTarget) : BudgetEvent

    /** 退出编辑页：清空编辑状态。 */
    data object CancelEdit : BudgetEvent

    /** 删除当前编辑目标已设的预算（软删 + 尽力推送墓碑）。 */
    data object DeleteBudget : BudgetEvent
}

/** deriveMonthBudget 的纯派生结果。 */
internal data class MonthBudgetDerivation(
    val totalBudget: Budget?,
    val monthExpenseMinor: Long,
    val categoryBudgets: List<CategoryBudgetState>,
    val expenseCategories: List<Category>,
    val monthBills: List<Bill>
)

/**
 * 纯函数：预算列表 + 本月账单 + 支出分类/子分类参考数据 → 分层派生结果（独立可测）。
 *
 * 归集口径：
 * - 总行：periodType == "MONTHLY" && monthStart == 本月 && categoryId == null && subCategoryId == null
 * - 分类行：categoryId != null && subCategoryId == null（同分类多行数据异常按合计归并为一行，
 *   防止计划页 LazyColumn 以 categoryId 作 key 时重复 key 崩溃）
 * - 子分类行：subCategoryId != null（同分类下重复子分类行同理按合计归并）
 * - 支出只计 BillType.EXPENSE：按 bill.categoryId 求和 → 分类行 expenseMinor；
 *   按 (categoryId, subCategoryName) 求和 → 子分类行 expenseMinor
 * - 子分类预算按 parentCategoryId == categoryId && name == subCategoryName 名称匹配
 * - 未设分类预算但本月有支出或该分类下有子分类预算的分类，补全为分类行（amountMinor = 0，UI 显示「未设」）
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
    val monthExpense = expenseBills.sumOf { it.amountMinor }
    val categoryExpense = expenseBills
        .groupBy { it.categoryId }
        .mapValues { (_, list) -> list.sumOf { it.amountMinor } }
    val subCategoryExpense = expenseBills
        .filter { !it.subCategoryName.isNullOrBlank() }
        .groupBy { it.categoryId to it.subCategoryName!! }
        .mapValues { (_, list) -> list.sumOf { it.amountMinor } }

    val categoryNameById = expenseCategories.associateBy { it.id }
    val subCategoryNameById = subCategories.associateBy { it.id }

    val subStatesByParent: Map<Long, List<SubCategoryBudgetState>> = subRows
        .groupBy { it.categoryId ?: 0L }
        .mapValues { (parentId, rows) ->
            // 同分类下重复的子分类预算行（数据异常）按合计归并为一行，子分类 id 保持唯一。
            rows.groupBy { it.subCategoryId!! }
                .map { (subCategoryId, duplicated) ->
                    val name = subCategoryNameById[subCategoryId]?.name ?: ""
                    SubCategoryBudgetState(
                        subCategoryId = subCategoryId,
                        name = name,
                        parentCategoryId = parentId,
                        amountMinor = duplicated.sumOf { it.amountMinor },
                        expenseMinor = subCategoryExpense[parentId to name] ?: 0L
                    )
                }
        }

    // 同分类多行预算（数据异常，如双端并发写入）按合计归并为一行——计划页 LazyColumn 以
    // categoryId 作 key，重复 key 会抛 IllegalArgumentException（进入计划页即崩溃）。
    // 口径与 ChallengeEngine 的「同月多行预算按合计计入」一致。
    val budgetedCategories = categoryRows
        .groupBy { it.categoryId!! }
        .map { (categoryId, rows) ->
            CategoryBudgetState(
                categoryId = categoryId,
                categoryName = categoryNameById[categoryId]?.name ?: "",
                amountMinor = rows.sumOf { it.amountMinor },
                expenseMinor = categoryExpense[categoryId] ?: 0L,
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
                amountMinor = 0L,
                expenseMinor = categoryExpense[category.id] ?: 0L,
                subBudgets = subStatesByParent[category.id] ?: emptyList()
            )
        }

    return MonthBudgetDerivation(
        totalBudget = total,
        monthExpenseMinor = monthExpense,
        categoryBudgets = budgetedCategories + completedCategories,
        expenseCategories = expenseCategories,
        monthBills = bills
    )
}

/** 维度 → 本月相关支出账单：总额=全部支出；分类=按 categoryId；子分类=categoryId + 子分类名（名称匹配口径与 deriveMonthBudget 一致）。 */
private fun billsForScope(bills: List<Bill>, target: BudgetEditTarget): List<Bill> = when (target) {
    BudgetEditTarget.Total -> bills.filter { it.billType == BillType.EXPENSE }
    is BudgetEditTarget.Category -> bills.filter { it.billType == BillType.EXPENSE && it.categoryId == target.categoryId }
    is BudgetEditTarget.SubCategory -> bills.filter {
        it.billType == BillType.EXPENSE &&
            it.categoryId == target.parentCategoryId &&
            it.subCategoryName == target.subCategoryName
    }
}

/** 按名称聚合构成占比（整数分求和，降序取前 6）。 */
private fun groupBreakdown(bills: List<Bill>, key: (Bill) -> String): List<SubBreakdown> =
    bills.groupBy(key)
        .mapValues { (_, list) -> list.sumOf { it.amountMinor } }
        .entries.sortedByDescending { it.value }
        .take(6)
        .map { SubBreakdown(it.key, it.value) }

/**
 * 纯函数：编辑目标 + 月度派生状态 → 编辑页完整状态（预填金额、每日趋势、构成占比、相关账单）。
 * 独立可测；prevMonthSamePeriodMinor 由调用方传入（VM 异步补齐后重放）。
 */
internal fun deriveEditState(
    target: BudgetEditTarget,
    state: BudgetState,
    prevMonthSamePeriodMinor: Long?
): BudgetEditState {
    val today = LocalDate.now(bookkeepingZone())
    val elapsedDays = today.dayOfMonth
    val daysInMonth = today.lengthOfMonth()
    val scopeBills = billsForScope(state.monthBills, target)

    val dayAmounts = scopeBills
        .groupBy { Instant.ofEpochMilli(it.date).atZone(bookkeepingZone()).toLocalDate().dayOfMonth }
        .mapValues { (_, list) -> list.sumOf { it.amountMinor } }
    val dailyTrend = (1..elapsedDays).map { day -> DailySpend(day, dayAmounts[day] ?: 0L) }

    val breakdown = when (target) {
        BudgetEditTarget.Total -> groupBreakdown(scopeBills) { it.categoryName }
        is BudgetEditTarget.Category -> groupBreakdown(scopeBills) { it.subCategoryName ?: "无子分类" }
        is BudgetEditTarget.SubCategory -> emptyList()
    }

    val existing = when (target) {
        BudgetEditTarget.Total -> state.totalBudget?.amountMinor?.takeIf { it > 0 }
        is BudgetEditTarget.Category ->
            state.categoryBudgets.firstOrNull { it.categoryId == target.categoryId }
                ?.amountMinor?.takeIf { it > 0 }
        is BudgetEditTarget.SubCategory ->
            state.categoryBudgets
                .firstOrNull { it.categoryId == target.parentCategoryId }
                ?.subBudgets?.firstOrNull { it.subCategoryId == target.subCategoryId }
                ?.amountMinor?.takeIf { it > 0 }
    }
    val expenseMinor = when (target) {
        BudgetEditTarget.Total -> state.monthExpenseMinor
        is BudgetEditTarget.Category ->
            state.categoryBudgets.firstOrNull { it.categoryId == target.categoryId }?.expenseMinor ?: 0L
        is BudgetEditTarget.SubCategory ->
            state.categoryBudgets
                .firstOrNull { it.categoryId == target.parentCategoryId }
                ?.subBudgets?.firstOrNull { it.subCategoryId == target.subCategoryId }?.expenseMinor ?: 0L
    }

    // 账单按日期倒序（同日按 id 倒序），取最近 10 笔供联动编辑。
    val sorted = scopeBills.sortedWith(compareByDescending<Bill> { it.date }.thenByDescending { it.id })
    return BudgetEditState(
        target = target,
        existingAmountMinor = existing,
        monthExpenseMinor = expenseMinor,
        remainingDays = daysInMonth - elapsedDays + 1,
        elapsedDays = elapsedDays,
        daysInMonth = daysInMonth,
        dailyTrend = dailyTrend,
        prevMonthSamePeriodMinor = prevMonthSamePeriodMinor,
        subBreakdown = breakdown,
        recentBills = sorted.take(10),
        billCount = sorted.size
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

    /** 编辑页状态（bill-edit 同款：编辑目标经共享 VM 传递，路由本身无参数）。 */
    private val _editState = MutableStateFlow(BudgetEditState())
    val editState: StateFlow<BudgetEditState> = _editState.asStateFlow()

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
                    lastMonthSurplusMinor = if (lastTotalBudget != null) {
                        lastTotalBudget.amountMinor - lastMonthExpense
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
                _state.update { cur ->
                    cur.copy(
                        totalBudget = derivation.totalBudget,
                        monthExpenseMinor = derivation.monthExpenseMinor,
                        categoryBudgets = derivation.categoryBudgets,
                        expenseCategories = derivation.expenseCategories,
                        monthBills = derivation.monthBills,
                        isLoading = false
                    )
                }
                // 编辑页开着时随最新数据实时刷新分析（账单联动编辑后不陈旧）。
                _editState.update { cur ->
                    val target = cur.target ?: return@update cur
                    deriveEditState(target, _state.value, cur.prevMonthSamePeriodMinor)
                }
            }
        }
    }

    fun onEvent(event: BudgetEvent) {
        when (event) {
            is BudgetEvent.SetBudget -> setBudget(event.amountMinor, event.categoryId, event.subCategoryId)
            is BudgetEvent.EditBudget -> editBudget(event.target)
            BudgetEvent.CancelEdit -> _editState.value = BudgetEditState()
            BudgetEvent.DeleteBudget -> deleteBudget()
        }
    }

    /** 编辑目标 → (categoryId, subCategoryId) 数据库维度。 */
    private fun BudgetEditTarget.scope(): Pair<Long?, Long?> = when (this) {
        BudgetEditTarget.Total -> null to null
        is BudgetEditTarget.Category -> categoryId to null
        is BudgetEditTarget.SubCategory -> parentCategoryId to subCategoryId
    }

    /**
     * 进入编辑页：从当前派生状态同步取数（预填金额 + 分析），无异步间隙，
     * 导航立即就能渲染出完整页面。上月同期对比异步补齐，就绪后原位刷新。
     */
    private fun editBudget(target: BudgetEditTarget) {
        _editState.value = deriveEditState(target, _state.value, prevMonthSamePeriodMinor = null)
        viewModelScope.launch {
            val prevStart = getMonthStart(-1)
            val curStart = getMonthStart()
            // 「上月同期」= 上月 1 日起，与本月初至今等长的窗口（业务时区月起点，整数分求和）。
            val elapsed = (System.currentTimeMillis() - curStart).coerceAtLeast(0L)
            val prevExpense = repository.getTotalExpense(prevStart, prevStart + elapsed)
            _editState.update { cur ->
                if (cur.target == target) cur.copy(prevMonthSamePeriodMinor = prevExpense) else cur
            }
        }
    }

    /** 删除当前编辑目标已设的预算：软删落库（deleted=1, dirty=1）并尽力推送墓碑。 */
    private fun deleteBudget() {
        val target = _editState.value.target ?: return
        val monthStart = getMonthStart()
        viewModelScope.launch {
            val (categoryId, subCategoryId) = target.scope()
            val existing = budgetRepository.findBudgetByScope(monthStart, categoryId, subCategoryId)
                ?: return@launch
            val tombstone = existing.copy(
                deleted = true,
                dirty = true,
                updatedAt = System.currentTimeMillis()
            )
            budgetRepository.upsertBudget(tombstone)
            syncManager?.let { launch { it.pushBudget(tombstone) } }
        }
    }

    private fun setBudget(amountMinor: Long, categoryId: Long?, subCategoryId: Long?) {
        val monthStart = getMonthStart()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // 按 (monthStart, categoryId, subCategoryId) 维度定位现有行：有则更新，无则新建。
            val existing = budgetRepository.findBudgetByScope(monthStart, categoryId, subCategoryId)
            val updated = existing
                ?.copy(amountMinor = amountMinor, updatedAt = now, dirty = true)
                ?: Budget(
                    monthStart = monthStart,
                    amountMinor = amountMinor,
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
