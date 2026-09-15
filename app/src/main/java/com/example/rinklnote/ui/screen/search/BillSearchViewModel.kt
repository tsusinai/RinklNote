package com.example.rinklnote.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

// ---------------------------------------------------------------------------
// 状态与事件
// ---------------------------------------------------------------------------

/** 种类筛选：全部 / 支出 / 收入。 */
enum class SearchTypeFilter { ALL, EXPENSE, INCOME }

/** 日期模式：全部 / 按天 / 按月 / 自定义起止。 */
enum class SearchDateMode { ALL, DAY, MONTH, RANGE }

/** 搜索筛选条件（关键词之外的维度，全部可选）。 */
@Immutable
data class BillSearchFilter(
    val type: SearchTypeFilter = SearchTypeFilter.ALL,
    val dateMode: SearchDateMode = SearchDateMode.ALL,
    /** 按天模式选中的日期；null = 未选（该维度不生效）。 */
    val day: LocalDate? = null,
    /** 按月模式选中的月份；null = 未选（该维度不生效）。 */
    val month: YearMonth? = null,
    /** 自定义起止（含两端）；任一端为 null = 该侧不限。 */
    val rangeStart: LocalDate? = null,
    val rangeEnd: LocalDate? = null,
    /** 分类筛选：精确匹配分类名；null = 未选（「全部」，该维度不生效）。 */
    val categoryName: String? = null
)

/** 搜索页状态（金额一律整数分）。 */
@Immutable
data class BillSearchState(
    /** 关键词：匹配 分类名 / 子分类名 / 备注 / 金额文本。 */
    val query: String = "",
    val filter: BillSearchFilter = BillSearchFilter(),
    /** 过滤后的结果，按日期倒序（同日保持仓库流既有的 sort_order/created_at 兜底顺序）。 */
    val results: List<Bill> = emptyList(),
    /** 结果总数。 */
    val totalCount: Int = 0,
    /** 结果中支出合计（分）。 */
    val expenseTotal: Long = 0L,
    /** 结果中收入合计（分）。 */
    val incomeTotal: Long = 0L,
    /**
     * 出现过的分类名列表（供分类筛选 box 行/扩展卡片展示）。
     * 取自**全量账单** distinct（不受当前筛选影响，否则选中某分类后其余 box 会消失无法切换），
     * 按出现频次降序（高频分类前置更快触达）、频次相同按名称升序（保证顺序稳定）。
     */
    val categories: List<String> = emptyList()
)

/** 搜索页事件（State + Event 模式，经 [BillSearchViewModel.onEvent] 分发）。 */
sealed interface BillSearchEvent {
    /** 关键词变化。 */
    data class QueryChanged(val query: String) : BillSearchEvent

    /** 切换种类筛选（全部/支出/收入）。 */
    data class TypeChanged(val type: SearchTypeFilter) : BillSearchEvent

    /** 切换日期模式；进入按天/按月时自动兜底选中「今天/本月」。 */
    data class DateModeChanged(val mode: SearchDateMode) : BillSearchEvent

    /** 按天模式选定日期。 */
    data class DayChanged(val day: LocalDate) : BillSearchEvent

    /** 按月模式选定月份（月份切换箭头步进）。 */
    data class MonthChanged(val month: YearMonth) : BillSearchEvent

    /** 自定义起止（两端一起提交；null = 该侧不限）。 */
    data class CustomRangeChanged(val start: LocalDate?, val end: LocalDate?) : BillSearchEvent

    /** 分类筛选：单选某分类（[category]）；null = 取消选择回「全部」。 */
    data class CategoryChanged(val category: String?) : BillSearchEvent

    /** 清空：关键词与全部筛选条件一键还原默认。 */
    data object Clear : BillSearchEvent
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * 账单搜索 ViewModel：数据取 [BillRepository.observeAllBills] 全量 Flow，
 * 在 VM 内 combine + **内存过滤**（不动 Dao / SQL / schema）。
 *
 * 过滤维度：
 * - 关键词：分类名 / 子分类名 / 备注 文本包含（忽略大小写）；
 *   纯数字或「¥xx」形式额外按**金额**匹配（分值相等，或「分→元」文本包含，见 [matchesQuery] 注释）；
 * - 种类：全部 / 支出 / 收入；
 * - 分类：全部 / 单个分类精确匹配（与关键词等其他维度 AND 叠加）；
 * - 日期：全部 / 某天 / 某月 / 自定义起止（业务时区 Asia/Shanghai 判定）。
 *
 * VM 只依赖 [BillRepository]（Factory 见类尾），主会话接线用：
 * `BillSearchViewModel.Factory(app.repository)`。
 */
class BillSearchViewModel(private val repository: BillRepository) : ViewModel() {

    /** 用户输入（关键词 + 筛选条件）——内部态；对外 [state] 由它与账单流 combine 派生。 */
    private val _state = MutableStateFlow(BillSearchState())

    /** 对外状态：账单流 × 输入态 内存过滤后的结果 + 汇总。 */
    val state: StateFlow<BillSearchState> = combine(
        repository.observeAllBills(),
        _state
    ) { bills, input ->
        deriveResults(bills, input)
    }
        .flowOn(Dispatchers.Default) // 内存过滤放后台线程，关键词连打不卡主线程
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BillSearchState()
        )

    fun onEvent(event: BillSearchEvent) {
        when (event) {
            is BillSearchEvent.QueryChanged -> _state.update { it.copy(query = event.query) }
            is BillSearchEvent.TypeChanged ->
                _state.update { it.copy(filter = it.filter.copy(type = event.type)) }
            is BillSearchEvent.DateModeChanged ->
                _state.update { it.copy(filter = withModeDefault(event.mode, it.filter)) }
            is BillSearchEvent.DayChanged ->
                _state.update { it.copy(filter = it.filter.copy(day = event.day)) }
            is BillSearchEvent.MonthChanged ->
                _state.update { it.copy(filter = it.filter.copy(month = event.month)) }
            is BillSearchEvent.CustomRangeChanged ->
                _state.update {
                    it.copy(
                        filter = it.filter.copy(rangeStart = event.start, rangeEnd = event.end)
                    )
                }
            is BillSearchEvent.CategoryChanged ->
                _state.update { it.copy(filter = it.filter.copy(categoryName = event.category)) }
            BillSearchEvent.Clear -> _state.update { BillSearchState() }
        }
    }

    /** 切换日期模式时的默认选择：按天兜底「今天」、按月兜底「本月」，避免切过去结果为空。 */
    private fun withModeDefault(mode: SearchDateMode, filter: BillSearchFilter): BillSearchFilter =
        when (mode) {
            SearchDateMode.ALL -> filter.copy(dateMode = mode)
            SearchDateMode.DAY -> filter.copy(
                dateMode = mode,
                day = filter.day ?: LocalDate.now(bookkeepingZone())
            )
            SearchDateMode.MONTH -> filter.copy(
                dateMode = mode,
                month = filter.month ?: YearMonth.now(bookkeepingZone())
            )
            // 起止任一端为 null = 该侧不限，两空 = 不做日期限制
            SearchDateMode.RANGE -> filter.copy(dateMode = mode)
        }

    /** 主会话接线用：BillSearchViewModel.Factory(app.repository)。 */
    class Factory(
        private val repository: BillRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BillSearchViewModel(repository) as T
    }
}

// ---------------------------------------------------------------------------
// 内存过滤（文件级私有，便于独立阅读；不依赖实例状态）
// ---------------------------------------------------------------------------

/** 全量账单 × 输入态 → 过滤排序 + 汇总。 */
private fun deriveResults(bills: List<Bill>, input: BillSearchState): BillSearchState {
    val filter = input.filter
    val results = bills.asSequence()
        .filter { it.matchesQuery(input.query) }
        .filter { it.matchesType(filter.type) }
        .filter { it.matchesCategory(filter.categoryName) }
        .filter { it.matchesDate(filter) }
        // sortedByDescending 稳定：同日内保留 observeAll 既有的 sort_order/created_at 兜底顺序
        .sortedByDescending { it.date }
        .toList()
    // 分类候选清单取自全量账单（distinct），与当前筛选无关，保证行内 box 始终可切换
    val categories = bills.groupingBy { it.categoryName }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
    return input.copy(
        categories = categories,
        results = results,
        totalCount = results.size,
        expenseTotal = results.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountMinor },
        incomeTotal = results.filter { it.billType == BillType.INCOME }.sumOf { it.amountMinor }
    )
}

/** 分类过滤：精确匹配分类名；null（「全部」）直接放行。 */
private fun Bill.matchesCategory(categoryName: String?): Boolean =
    categoryName == null || this.categoryName == categoryName

/** 种类过滤；ALL 直接放行。 */
private fun Bill.matchesType(type: SearchTypeFilter): Boolean = when (type) {
    SearchTypeFilter.ALL -> true
    SearchTypeFilter.EXPENSE -> billType == BillType.EXPENSE
    SearchTypeFilter.INCOME -> billType == BillType.INCOME
}

/**
 * 日期过滤（业务时区）。Bill.date 是「当日 0 点」时间戳，按天/按月直接换算本地日期比较。
 * 起止任一端为 null = 该侧不限；两端都设但 start > end 时视为空区间（无匹配），由 UI 层提示。
 */
private fun Bill.matchesDate(filter: BillSearchFilter): Boolean {
    val localDate = Instant.ofEpochMilli(date).atZone(bookkeepingZone()).toLocalDate()
    return when (filter.dateMode) {
        SearchDateMode.ALL -> true
        SearchDateMode.DAY -> filter.day == null || localDate == filter.day
        SearchDateMode.MONTH -> filter.month == null || YearMonth.from(localDate) == filter.month
        SearchDateMode.RANGE -> {
            val start = filter.rangeStart
            val end = filter.rangeEnd
            (start == null || !localDate.isBefore(start)) &&
                (end == null || !localDate.isAfter(end))
        }
    }
}

/**
 * 关键词匹配：
 * - 空白关键词放行；
 * - 「¥xx / ￥xx / 纯数字 / 千分位数字」视为**金额查询**：分值相等（[Money.parseMinor]），
 *   或「分→元」文本包含（[Money.toYuanInputString]，不带千分位与补零，支持输一半，如「25」中「25.5」）；
 * - 金额查询同时**回落文本匹配**（分类/子分类/备注），避免关键词里恰好含数字的分类被漏掉；
 * - 其余一律按文本包含匹配（忽略大小写）。
 */
private fun Bill.matchesQuery(rawQuery: String): Boolean {
    val q = rawQuery.trim()
    if (q.isEmpty()) return true

    // 去掉货币符号与千分位后再判断是否是纯数字（含小数点）
    val amountQuery = q.removePrefix("¥").removePrefix("￥").replace(",", "").trim()
    val looksLikeAmount = amountQuery.isNotEmpty() &&
        amountQuery.all { it.isDigit() || it == '.' } &&
        amountQuery.any { it.isDigit() }

    if (looksLikeAmount) {
        val exactMinor = Money.parseMinor(amountQuery)
        if (exactMinor != null && exactMinor == amountMinor) return true
        // 分→元文本包含：2500 分 → "25"；2550 分 → "25.5"；查询 "25.5" / "25" 均可命中
        if (Money.toYuanInputString(amountMinor).contains(amountQuery)) return true
    }
    return matchesText(q)
}

/** 文本维度：分类名 / 子分类名 / 备注（任一命中即可，忽略大小写）。 */
private fun Bill.matchesText(q: String): Boolean {
    if (categoryName.contains(q, ignoreCase = true)) return true
    if (subCategoryName?.contains(q, ignoreCase = true) == true) return true
    if (remark?.contains(q, ignoreCase = true) == true) return true
    return false
}
