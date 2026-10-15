package com.example.rinklnote.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    private val totals = WidgetTotals(
        todayExpenseMinor = 4800, todayIncomeMinor = 12300,
        monthExpenseMinor = 128600, budgetMinor = 300000,
        categories = listOf(WidgetCategoryTotal("医疗", 62800), WidgetCategoryTotal("三餐", 28200),
            WidgetCategoryTotal("娱乐", 19000), WidgetCategoryTotal("交通", 18600))
    )

    @Test fun privacyRemovesAmountsRatiosAndPrivateRanking() {
        val hidden = presentWidgetTotals(totals, true)
        assertEquals(WidgetPresentation.protected(), hidden)
        assertNull(hidden.progress)
        assertTrue(hidden.categories.all { it.amount == WidgetPresentation.MASK && it.progress == null })
        assertEquals(WidgetPresentation.PUBLIC_CATEGORY_NAMES, hidden.categories.map { it.name })
        assertFalse(hidden.toString().contains("医疗"))
        assertFalse(hidden.toString().contains("1286"))
        // 不能通过预算是否存在、超支或消费数据的变化推断隐私态下的财务情况。
        assertEquals(hidden, presentWidgetTotals(WidgetTotals(), true))
        assertEquals(hidden, presentWidgetTotals(totals.copy(budgetMinor = 100), true))
    }

    @Test fun visibleTotalsUseIntegerMoneyAndCorrectBudgetRatio() {
        val visible = presentWidgetTotals(totals, false)
        assertEquals("¥48.00", visible.todayExpense)
        assertEquals("¥1,286.00", visible.monthExpense)
        assertEquals("¥1,714.00", visible.remaining)
        assertEquals("已用 43%", visible.usage)
        assertEquals(0.42866668f, visible.progress!!, 0.00001f)
        assertEquals(WidgetBudgetState.AVAILABLE, visible.budgetState)
    }

    @Test fun missingZeroAndNegativeBudgetDoNotDivideByZero() {
        listOf(null, 0L, -1L).forEach { budget ->
            val result = presentWidgetTotals(totals.copy(budgetMinor = budget), false)
            assertEquals(WidgetBudgetState.UNSET, result.budgetState)
            assertNull(result.progress)
            assertEquals("未设置", result.remaining)
        }
    }

    @Test fun overspendingClampsBarButPreservesActualAmountAndPercentage() {
        val result = presentWidgetTotals(totals.copy(budgetMinor = 100000), false)
        assertEquals(WidgetBudgetState.OVERSPENT, result.budgetState)
        assertEquals("¥286.00", result.remaining)
        assertEquals(1f, result.progress!!, 0f)
        assertEquals("已用 129%", result.usage)
    }

    @Test fun rankingIsLimitedAndDoesNotProduceInvalidBarsForEmptyOrZeroTotals() {
        val extra = totals.categories + listOf(WidgetCategoryTotal("日用", 5000), WidgetCategoryTotal("学习", 0))
        val result = presentWidgetTotals(totals.copy(categories = extra.reversed()), false)
        assertEquals(4, result.categories.size)
        assertEquals("医疗", result.categories.first().name)
        assertEquals(1f, result.categories.first().progress!!, 0f)
        assertTrue(result.categories.all { it.progress!!.isFinite() && it.progress in 0f..1f })
        assertTrue(presentWidgetTotals(WidgetTotals(), false).categories.isEmpty())
    }
}
