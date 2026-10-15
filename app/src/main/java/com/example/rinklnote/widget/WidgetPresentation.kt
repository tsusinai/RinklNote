package com.example.rinklnote.widget

import com.example.rinklnote.util.Money
import kotlin.math.roundToInt

/** 桌面只接收汇总后的展示数据，不传递备注、位置、账户或账单明细。 */
internal data class WidgetCategoryTotal(val name: String, val amountMinor: Long)

internal data class WidgetTotals(
    val todayExpenseMinor: Long = 0,
    val todayIncomeMinor: Long = 0,
    val monthExpenseMinor: Long = 0,
    val budgetMinor: Long? = null,
    val categories: List<WidgetCategoryTotal> = emptyList()
)

internal data class WidgetCategoryDisplay(val name: String, val amount: String, val progress: Float?)

internal enum class WidgetBudgetState { PROTECTED, UNSET, AVAILABLE, OVERSPENT }

/** 掩码在交给桌面宿主前完成；无障碍文字、进度和排序也只能读取这份数据。 */
internal data class WidgetPresentation(
    val hidden: Boolean,
    val todayExpense: String,
    val todayIncome: String,
    val monthExpense: String,
    val remaining: String,
    val spent: String,
    val limit: String,
    val usage: String,
    val progress: Float?,
    val budgetState: WidgetBudgetState,
    val categories: List<WidgetCategoryDisplay>
) {
    companion object {
        const val MASK = "¥•••"
        val PUBLIC_CATEGORY_NAMES = listOf("三餐", "日用", "娱乐", "交通")

        fun protected() = WidgetPresentation(
            hidden = true,
            todayExpense = MASK, todayIncome = MASK, monthExpense = MASK,
            remaining = MASK, spent = MASK, limit = MASK, usage = "已隐藏",
            progress = null, budgetState = WidgetBudgetState.PROTECTED,
            // 固定公开分类和顺序，不能让消费排行或分类数量绕过金额掩码。
            categories = PUBLIC_CATEGORY_NAMES.map { WidgetCategoryDisplay(it, MASK, null) }
        )
    }
}

internal fun presentWidgetTotals(totals: WidgetTotals, hidden: Boolean): WidgetPresentation {
    if (hidden) return WidgetPresentation.protected()
    val budget = totals.budgetMinor?.takeIf { it > 0 }
    val remaining = budget?.minus(totals.monthExpenseMinor)
    val ratio = budget?.let { totals.monthExpenseMinor.toFloat() / it.toFloat() }
    val ranked = totals.categories.filter { it.amountMinor > 0 }.sortedByDescending { it.amountMinor }.take(4)
    val largest = ranked.firstOrNull()?.amountMinor
    return WidgetPresentation(
        hidden = false,
        todayExpense = widgetMoney(totals.todayExpenseMinor),
        todayIncome = widgetMoney(totals.todayIncomeMinor),
        monthExpense = widgetMoney(totals.monthExpenseMinor),
        remaining = remaining?.let { widgetMoney(if (it < 0) -it else it) } ?: "未设置",
        spent = widgetMoney(totals.monthExpenseMinor),
        limit = budget?.let(::widgetMoney) ?: "未设置",
        usage = ratio?.let { "已用 ${(it * 100).roundToInt()}%" } ?: "设置月预算",
        progress = ratio?.coerceIn(0f, 1f),
        budgetState = when {
            budget == null -> WidgetBudgetState.UNSET
            remaining != null && remaining < 0 -> WidgetBudgetState.OVERSPENT
            else -> WidgetBudgetState.AVAILABLE
        },
        categories = ranked.map {
            WidgetCategoryDisplay(it.name, widgetMoney(it.amountMinor),
                largest?.let { max -> (it.amountMinor.toFloat() / max.toFloat()).coerceIn(0f, 1f) })
        }
    )
}

/** 显式带货币符号，避免应用尚未启动时的进程偏好影响桌面；金额换算仍统一走 Money。 */
internal fun widgetMoney(minor: Long): String = "¥${Money.formatPlain(minor)}"
