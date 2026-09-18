package com.example.rinklnote.util

import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 年度账单聚合（纯 JVM，可单测）——「年度账单分享图」的数据源。
 *
 * 口径：
 * - 年份判定：bill.date（当日 0 点 epoch millis）按业务时区转 LocalDate 后取 year；
 * - 支出/收入分别合计（整数分）；结余 = 收入 − 支出；
 * - Top5 分类只统计**支出**（金额降序，最多 5 个，分类名沿用账单快照 categoryName）；
 * - 调用方传入的 bills 应来自 `observeAllBills()`（已滤 deleted=0）。
 */
data class AnnualStats(
    val year: Int,
    /** 1~12 月支出合计（整数分，index 0 = 1 月）。 */
    val monthlyExpenseMinor: List<Long>,
    /** 1~12 月收入合计（整数分）。 */
    val monthlyIncomeMinor: List<Long>,
    val totalExpenseMinor: Long,
    val totalIncomeMinor: Long,
    /** 年度支出 Top5 分类（金额降序）。 */
    val topCategories: List<CategoryTotal>
) {
    data class CategoryTotal(val categoryName: String, val amountMinor: Long)

    val netMinor: Long get() = totalIncomeMinor - totalExpenseMinor

    /** 有账单的月份数（供贺词/文案使用）。 */
    val activeMonths: Int get() = monthlyExpenseMinor.count { it > 0 } + monthlyIncomeMinor.count { it > 0 }
}

fun aggregateAnnualStats(bills: List<Bill>, year: Int, zone: ZoneId = bookkeepingZone()): AnnualStats {
    val monthlyExpense = MutableList(12) { 0L }
    val monthlyIncome = MutableList(12) { 0L }
    var totalExpense = 0L
    var totalIncome = 0L
    val categoryTotals = HashMap<String, Long>()

    for (bill in bills) {
        val date: LocalDate = Instant.ofEpochMilli(bill.date).atZone(zone).toLocalDate()
        if (date.year != year) continue
        val monthIndex = date.monthValue - 1
        when (bill.billType) {
            BillType.EXPENSE -> {
                monthlyExpense[monthIndex] += bill.amountMinor
                totalExpense += bill.amountMinor
                categoryTotals[bill.categoryName] = (categoryTotals[bill.categoryName] ?: 0L) + bill.amountMinor
            }
            BillType.INCOME -> {
                monthlyIncome[monthIndex] += bill.amountMinor
                totalIncome += bill.amountMinor
            }
        }
    }

    return AnnualStats(
        year = year,
        monthlyExpenseMinor = monthlyExpense,
        monthlyIncomeMinor = monthlyIncome,
        totalExpenseMinor = totalExpense,
        totalIncomeMinor = totalIncome,
        topCategories = categoryTotals.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { AnnualStats.CategoryTotal(it.key, it.value) }
    )
}

/**
 * 小盘贺词：按年度数据挑一句（纯文案，不引图片）。全程中文、带小盘口吻。
 */
fun annualGreeting(stats: AnnualStats): String = when {
    stats.totalExpenseMinor == 0L && stats.totalIncomeMinor == 0L ->
        "${stats.year} 年还没开始记，小盘等你一起来！"
    stats.netMinor > 0 ->
        "${stats.year} 年攒下啦！结余 ${Money.format(stats.netMinor)}，小盘给你点赞～"
    stats.netMinor == 0L ->
        "${stats.year} 年收支刚刚好，小盘陪你稳稳走过这一年～"
    else ->
        "${stats.year} 年辛苦啦，新的一年一起把小金库慢慢养大，小盘同行～"
}
