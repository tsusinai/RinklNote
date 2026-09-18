package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.repository.AccountRepository
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.MonthDetailData
import com.example.rinklnote.domain.buildMonthDetail
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.today
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import com.example.rinklnote.util.reorderRanks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime

/** 首页顶部横幅背景按当前时段切换：早晨/白天/傍晚/深夜 → 4 张图。 */
enum class DayPart {
    MORNING, DAY, EVENING, NIGHT;

    companion object {
        fun current(now: LocalTime = LocalTime.now()): DayPart {
            val h = now.hour
            return when {
                h in 5..11 -> MORNING
                h in 12..16 -> DAY
                h in 17..19 -> EVENING
                else -> NIGHT
            }
        }
    }
}

/** AI 当月总结缓存的刷新周期（毫秒）。 */
private const val AI_SUMMARY_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

@androidx.compose.runtime.Immutable
data class BookkeepingMonthState(
    val bills: List<Bill> = emptyList(),
    val totalExpense: Long = 0L,
    val totalIncome: Long = 0L,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val currentDate: Long = System.currentTimeMillis(),
    // Offset of the month currently shown: 0 = current month, -1 = previous.
    // Drives the main list, the chart and the totals together.
    val selectedMonthOffset: Int = 0,
    // AI 对当月总结建议：月视图 SummaryBar 底部展示。null = 未加载/失败不展示。
    val aiSummary: String? = null,
    val aiSummaryLoading: Boolean = false,
    // 顶部横幅背景：由本 ViewModel 按当前时段给出（决定用哪张图）。
    val dayPart: DayPart = DayPart.current(),
    // 当月图表聚合数据：每日支出、分类占比、日序列。随当月账单算一次（不依赖 UI）。
    val monthDetail: MonthDetailData = MonthDetailData(emptyMap(), emptyList(), emptyList(), 1f)
)

/** 编辑抽屉所需的低频状态：编辑目标 + 分类/账户参考数据。
 *  与月视图状态拆开，避免账单 Flow 每次发射都牵动编辑页重组。 */
@androidx.compose.runtime.Immutable
data class BookkeepingEditState(
    val editingBill: Bill? = null,
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList()
) {
    val categories: List<Category>
        get() = if (editingBill?.billType == BillType.INCOME) incomeCategories else expenseCategories
}

sealed interface BookkeepingEvent {
    data object Refresh : BookkeepingEvent

    /** 首页下拉刷新：拉一次服务端同步，再重算当月合计。 */
    data object PullRefresh : BookkeepingEvent
    data class EditBill(val bill: Bill) : BookkeepingEvent
    data object CancelEdit : BookkeepingEvent
    data class ConfirmEdit(val bill: Bill) : BookkeepingEvent
    data class DeleteBill(val bill: Bill) : BookkeepingEvent
    /** 拖动重排：newOrder 为该日账单自上而下的新顺序（包含被拖动项）。 */
    data class ReorderBills(val newOrder: List<Bill>) : BookkeepingEvent
    data class SelectMonth(val offset: Int) : BookkeepingEvent
}

class BookkeepingViewModel(
    private val repository: BillRepository,
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager? = null,
    private val api: ApiService? = null
) : ViewModel() {

    private val _monthState = MutableStateFlow(BookkeepingMonthState())
    val monthState: StateFlow<BookkeepingMonthState> = _monthState.asStateFlow()

    private val _editState = MutableStateFlow(BookkeepingEditState())
    val editState: StateFlow<BookkeepingEditState> = _editState.asStateFlow()

    private var billCollectorJob: Job? = null

    init {
        // Single long-lived bill collector — never leaks
        collectBills()

        // AI 当月总结：app 启动即拉取一次，并周期性刷新缓存（与切换月份无关，跨月时总结栏关闭）
        refreshAiSummary()
        startAiSummaryPeriodic()

        // Reference data for the edit overlay
        viewModelScope.launch {
            repository.expenseCategories.collect { cats ->
                _editState.update { it.copy(expenseCategories = cats) }
            }
        }
        viewModelScope.launch {
            repository.incomeCategories.collect { cats ->
                _editState.update { it.copy(incomeCategories = cats) }
            }
        }
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { accts ->
                _editState.update { it.copy(accounts = accts) }
            }
        }
    }

    fun onEvent(event: BookkeepingEvent) {
        when (event) {
            is BookkeepingEvent.Refresh -> collectBills()
            is BookkeepingEvent.PullRefresh -> pullRefresh()
            is BookkeepingEvent.EditBill -> _editState.update { it.copy(editingBill = event.bill) }
            is BookkeepingEvent.CancelEdit -> _editState.update { it.copy(editingBill = null) }
            is BookkeepingEvent.ConfirmEdit -> confirmEdit(event.bill)
            is BookkeepingEvent.DeleteBill -> deleteBill(event.bill)
            is BookkeepingEvent.ReorderBills -> reorderBills(event.newOrder)
            is BookkeepingEvent.SelectMonth -> selectMonth(event.offset)
        }
    }

    /** 合并账单列表 + 收支合计，一次订阅同时驱动 list / chart / totals。
     *  三者都来自 Room 响应式 Flow，账单变更时自动重算，无需手动 refreshTotals。 */
    private fun collectBills() {
        billCollectorJob?.cancel()
        val offset = _monthState.value.selectedMonthOffset
        billCollectorJob = viewModelScope.launch {
            val monthStart = getMonthStart(offset)
            val nextMonthStart = getNextMonthStart(offset)
            val daysInMonth = Instant.ofEpochMilli(monthStart)
                .atZone(bookkeepingZone()).toLocalDate().lengthOfMonth()
            combine(
                repository.observeBillsByMonth(monthStart, nextMonthStart),
                repository.observeTotalExpense(monthStart, nextMonthStart),
                repository.observeTotalIncome(monthStart, nextMonthStart)
            ) { bills, expense, income -> Triple(bills, expense, income) }
                .distinctUntilChanged()
                .collect { (bills, expense, income) ->
                    _monthState.update {
                        it.copy(
                            bills = bills,
                            totalExpense = expense,
                            totalIncome = income,
                            isLoading = false,
                            monthDetail = buildMonthDetail(bills, daysInMonth)
                        )
                    }
                }
        }
    }

    /** Switches every month-scoped view (list/chart/totals) to a different month. */
    fun selectMonth(offset: Int) {
        if (_monthState.value.selectedMonthOffset == offset) return
        _monthState.update { it.copy(selectedMonthOffset = offset) }
        collectBills()
    }

    /** 首页下拉刷新：先拉一次服务端同步（账单经 syncManager 落库，Room 响应式自动刷新列表与合计）。 */
    private fun pullRefresh() {
        if (_monthState.value.isRefreshing) return
        _monthState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                syncManager?.sync()
            } catch (_: Exception) {
                // 同步失败不阻断刷新——本地数据保持原样，仅供下次重试
            }
            refreshAiSummary()
            _monthState.update { it.copy(isRefreshing = false) }
        }
    }

    /** 触发一次当月总结刷新（供启动、下拉刷新、周期任务调用）。 */
    private fun refreshAiSummary() {
        viewModelScope.launch { fetchAiSummary() }
    }

    /** 拉取「当月」AI 总结建议并缓存到 state.aiSummary。跨月时总结栏关闭，但缓存照常刷新。
     *  失败/未登录不阻断——仅清空缓存内容。 */
    private suspend fun fetchAiSummary() {
        val api = api ?: return
        _monthState.update { it.copy(aiSummaryLoading = true) }
        try {
            // 始终缓存当月：与 selectedMonthOffset 无关
            val d = today()
            val month = "%d-%02d".format(d.year, d.monthValue)
            val r = api.getMonthlySummary(month)
            val text = buildString {
                if (r.summary.isNotBlank()) append(r.summary.trim())
                r.highlights.take(3).forEach {
                    if (isNotEmpty()) append("\n")
                    append("- ").append(it)
                }
            }.trim()
            _monthState.update { it.copy(aiSummary = text.ifBlank { null }, aiSummaryLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _monthState.update { it.copy(aiSummary = null, aiSummaryLoading = false) }
        }
    }

    /** app 启动后按固定周期刷新当月总结缓存。无 API（未接线/测试）时无需周期刷新。 */
    private fun startAiSummaryPeriodic() {
        if (api == null) return
        viewModelScope.launch {
            while (isActive) {
                delay(AI_SUMMARY_REFRESH_INTERVAL_MS)
                fetchAiSummary()
            }
        }
    }

    private fun confirmEdit(bill: Bill) {
        val dirtyBill = bill.copy(dirty = true, updatedAt = System.currentTimeMillis())
        viewModelScope.launch {
            repository.updateBill(dirtyBill)
            syncManager?.let { launch { it.pushBill(dirtyBill) } }
            _editState.update { it.copy(editingBill = null) }
        }
    }

    private fun deleteBill(bill: Bill) {
        viewModelScope.launch {
            repository.deleteBill(bill) // soft delete locally (dirty=1, deleted=1)
            syncManager?.let { launch { it.pushBill(bill.copy(deleted = true, dirty = true)) } }
        }
    }

    /** 拖动重排提交：按新序赋 sort_order 名次并整批置脏，逐条推送服务端。 */
    private fun reorderBills(newOrder: List<Bill>) {
        if (newOrder.isEmpty()) return
        val ranks = reorderRanks(newOrder.size)
        val now = System.currentTimeMillis()
        val reRanked = newOrder.mapIndexed { i, bill ->
            bill.copy(sortOrder = ranks[i], dirty = true, updatedAt = now)
        }
        viewModelScope.launch {
            repository.reorderBills(reRanked)
            syncManager?.let { sm -> reRanked.forEach { bill -> launch { sm.pushBill(bill) } } }
        }
    }

    /** 二级分类按所属一级分类延时加载（编辑页点开某分类时才拉取），避免一次性全量. */
    suspend fun subCategories(parentId: Long): List<SubCategory> = repository.getSubCategories(parentId)

    class Factory(
        private val repository: BillRepository,
        private val accountRepository: AccountRepository,
        private val syncManager: SyncManager? = null,
        private val api: ApiService? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookkeepingViewModel(repository, accountRepository, syncManager, api) as T
        }
    }
}
