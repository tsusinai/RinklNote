package com.example.rinklnote.data.db.entity

/**
 * 日粒度统计（一天一行），由 BillDao.observeDailySpendStats 按 bills.date 聚合产出，
 * 驱动省钱挑战 / 打卡墙 / 成就的全部派生口径。
 */
data class DailySpendStat(
    val dayStart: Long,      // 当日 0 点 epoch millis（= bills.date 分组键）
    val billCount: Int,      // 当日记账笔数（含收入）
    val expenseMinor: Long,  // 当日支出合计（整数分）
)
