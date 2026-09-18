package com.example.rinklnote.util

import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import kotlin.math.floor

/**
 * 消费地理画像纯计算（Task 4.3，纯 JVM 可单测）：带位置账单 → 0.01° 网格聚合排行。
 *
 * 口径：
 * - **只统计主动打点的支出账单**（bills.latitude/longitude 非空；收入不进「消费」画像，
 *   未打点账单无坐标天然不参与）——数据本就稀疏，诚实展示；
 * - 网格边长 [GRID_DEG] = 0.01 度（≈1.1 km，「商圈/片区」粒度）；网格键 = floor(度 ÷ 0.01)，
 *   南半球/西经（负值）用 floor 保证网格全域连续；
 * - 输出按网格内支出合计降序（排行榜）；topCategory = 网格内金额最大的分类。
 */
object SpendGeoProfile {

    /** 网格边长（度）：0.01° ≈ 1.1 km。 */
    const val GRID_DEG = 0.01

    /** 一个网格（片区）的聚合结果。[cellLat]/[cellLng] 为网格**中心**坐标（度）。 */
    data class CellRank(
        val cellLat: Double,
        val cellLng: Double,
        val billCount: Int,
        /** 网格内支出合计（整数分）。 */
        val totalMinor: Long,
        /** 网格内金额最大的分类名；无分类时 null。 */
        val topCategory: String?
    )

    /**
     * 带位置账单 → 网格聚合排行（支出合计降序）。无带位置支出账单返回空列表。
     * 入参可传全量账单（未打点 / 收入在函数内被过滤），调用方无需预处理。
     */
    fun aggregateGrid(bills: List<Bill>): List<CellRank> {
        data class CellAcc(
            var count: Int = 0,
            var total: Long = 0L,
            val byCategory: HashMap<String, Long> = HashMap()
        )

        val cells = HashMap<Pair<Long, Long>, CellAcc>()
        for (bill in bills) {
            if (bill.billType != BillType.EXPENSE) continue
            val lat = bill.latitude ?: continue
            val lng = bill.longitude ?: continue
            val key = floor(lat / GRID_DEG).toLong() to floor(lng / GRID_DEG).toLong()
            val acc = cells.getOrPut(key) { CellAcc() }
            acc.count++
            acc.total += bill.amountMinor
            acc.byCategory[bill.categoryName] = (acc.byCategory[bill.categoryName] ?: 0L) + bill.amountMinor
        }

        return cells.entries
            .sortedByDescending { it.value.total }
            .map { (key, acc) ->
                CellRank(
                    cellLat = (key.first + 0.5) * GRID_DEG,
                    cellLng = (key.second + 0.5) * GRID_DEG,
                    billCount = acc.count,
                    totalMinor = acc.total,
                    topCategory = acc.byCategory.entries.maxByOrNull { it.value }?.key
                )
            }
    }

    /** 热力圆点半径（米）：按网格支出占榜首比例从 [MIN_RADIUS_M] 到 [MAX_RADIUS_M] 线性放大。 */
    fun radiusMeters(totalMinor: Long, topTotalMinor: Long): Double {
        if (topTotalMinor <= 0L) return MIN_RADIUS_M
        val ratio = (totalMinor.toFloat() / topTotalMinor).coerceIn(0f, 1f)
        return MIN_RADIUS_M + (MAX_RADIUS_M - MIN_RADIUS_M) * ratio
    }

    private const val MIN_RADIUS_M = 250.0
    private const val MAX_RADIUS_M = 1100.0
}
