package com.example.rinklnote.domain

import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant

/** 饼图切片：分类名 + 金额 + 占比(0..1)。 */
data class PieSlice(val name: String, val amount: Double, val pct: Float)

/** 单月支出按分类占比 → 切片；>6 类时取前 5 + 「其他」，小项归一。 */
fun buildPieSlices(expense: List<Bill>): List<PieSlice> {
    val total = expense.sumOf { it.amount }
    if (total <= 0.0) return emptyList()
    val byCategory = expense.groupBy { it.categoryName }
        .mapValues { it.value.sumOf { b -> b.amount } }
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

    val keep: List<Pair<String, Double>> = if (byCategory.size <= 6) {
        byCategory
    } else {
        byCategory.take(5) + ("其他" to byCategory.drop(5).sumOf { it.second })
    }
    return keep.map { (name, amount) -> PieSlice(name, amount, (amount / total).toFloat()) }
}

/** 单月图表聚合数据：每日支出、分类占比、当月日序列、序列最大值。随当月账单算一次，供 UI 渲染。 */
data class MonthDetailData(
    val dayAmounts: Map<Int, Double>,
    val pieSlices: List<PieSlice>,
    val daySeries: List<Float>,
    val maxSeries: Float,
)

/** 由当月账单（仓库已过滤为「当月」窗口）聚合出图表数据。 */
fun buildMonthDetail(bills: List<Bill>, daysInMonth: Int): MonthDetailData {
    val dayAmounts = bills.filter { it.billType == "EXPENSE" }
        .groupBy { Instant.ofEpochMilli(it.date).atZone(bookkeepingZone()).toLocalDate().dayOfMonth }
        .mapValues { it.value.sumOf { b -> b.amount } }
    val pieSlices = buildPieSlices(bills.filter { it.billType == "EXPENSE" })
    val daySeries = (1..daysInMonth).map { day -> dayAmounts[day]?.toFloat() ?: 0f }
    val maxSeries = daySeries.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    return MonthDetailData(dayAmounts, pieSlices, daySeries, maxSeries)
}
