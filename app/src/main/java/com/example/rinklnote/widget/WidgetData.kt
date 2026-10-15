package com.example.rinklnote.widget

import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.util.bookkeepingZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

internal data class WidgetShortcut(val name: String, val categoryId: Long?)

internal data class WidgetContent(
    val presentation: WidgetPresentation = WidgetPresentation.protected(),
    val month: Int = LocalDate.now(bookkeepingZone()).monthValue,
    val shortcuts: List<WidgetShortcut> = listOf("三餐", "交通", "日用").map { WidgetShortcut(it, null) },
    val quickAmounts: List<Long> = SettingsManager.DEFAULT_QUICK_AMOUNTS
)

/** 隐私态只加载公开快捷入口，不读取真实账单和预算；进程冷启动也先显示保护态。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun widgetContentFlow(app: RinklNoteApp): Flow<WidgetContent> {
    val dates = flow {
        while (true) {
            emit(LocalDate.now(bookkeepingZone()))
            delay(60_000)
        }
    }.distinctUntilChanged()
    return combine(app.settingsManager.widgetPreferences, BalancePrivacy.hidden, dates) { prefs, hidden, date ->
        Triple(prefs, hidden, date)
    }.flatMapLatest { (prefs, hidden, date) ->
        val protected = prefs.privacyEnabled || prefs.balanceHidden || hidden
        val presentation = if (protected) {
            flowOf(WidgetPresentation.protected())
        } else {
            val zone = bookkeepingZone()
            val start = date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val today = date.atStartOfDay(zone).toInstant().toEpochMilli()
            combine(app.repository.observeBillsByMonth(start, end), app.budgetRepository.observeBudgets()) { bills, budgets ->
                val active = bills.filterNot { it.deleted }
                val expenses = active.filter { it.billType == BillType.EXPENSE }
                val budget = budgets.firstOrNull {
                    !it.deleted && it.monthStart == start && it.categoryId == null && it.subCategoryId == null
                }
                presentWidgetTotals(WidgetTotals(
                    todayExpenseMinor = expenses.filter { it.date == today }.sumOf { it.amountMinor },
                    todayIncomeMinor = active.filter { it.date == today && it.billType == BillType.INCOME }.sumOf { it.amountMinor },
                    monthExpenseMinor = expenses.sumOf { it.amountMinor },
                    budgetMinor = budget?.amountMinor,
                    categories = expenses.groupBy { it.categoryName }.map { (name, rows) ->
                        WidgetCategoryTotal(name, rows.sumOf { it.amountMinor })
                    }
                ), hidden = false)
            }
        }
        combine(presentation, app.repository.expenseCategories) { display, categories ->
            WidgetContent(
                presentation = display,
                month = date.monthValue,
                // 快捷分类固定为公开入口，不能借此泄露个人消费排行。
                shortcuts = listOf("三餐", "交通", "日用").map { name ->
                    WidgetShortcut(name, categories.firstOrNull { it.name == name }?.id)
                },
                // 隐私态展示固定的公开预设，不把个人自定义金额交给桌面宿主。
                quickAmounts = if (protected) SettingsManager.DEFAULT_QUICK_AMOUNTS else prefs.quickAmounts
            )
        }
    }.distinctUntilChanged()
}
