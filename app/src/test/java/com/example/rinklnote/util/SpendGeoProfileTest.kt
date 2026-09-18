package com.example.rinklnote.util

import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消费地理画像网格聚合单测（Task 4.3）：0.01° 网格归属、支出过滤、排行排序、
 * Top 分类、热力半径映射。
 */
class SpendGeoProfileTest {

    private fun bill(
        lat: Double?,
        lng: Double?,
        amountMinor: Long,
        category: String = "三餐",
        type: BillType = BillType.EXPENSE
    ): Bill = Bill(
        amountMinor = amountMinor,
        billType = type,
        categoryId = 1,
        categoryName = category,
        accountId = 1,
        date = 0L,
        latitude = lat,
        longitude = lng
    )

    @Test
    fun `同一网格账单聚合`() {
        // 两笔都在网格 cell(3123, 12147) 内（0.01° 网格）
        val ranks = SpendGeoProfile.aggregateGrid(
            listOf(
                bill(31.231, 121.471, 1000),
                bill(31.234, 121.474, 2000)
            )
        )
        assertEquals(1, ranks.size)
        assertEquals(2, ranks[0].billCount)
        assertEquals(3000L, ranks[0].totalMinor)
        // 网格中心 = (3123 + 0.5) × 0.01 / 100 … 验证中心值
        assertEquals(31.235, ranks[0].cellLat, 1e-9)
        assertEquals(121.475, ranks[0].cellLng, 1e-9)
    }

    @Test
    fun `相邻网格分开聚合`() {
        val ranks = SpendGeoProfile.aggregateGrid(
            listOf(
                bill(31.231, 121.471, 1000),
                bill(31.245, 121.471, 500)   // 北移跨过网格边界
            )
        )
        assertEquals(2, ranks.size)
    }

    @Test
    fun `按支出合计降序排行`() {
        val ranks = SpendGeoProfile.aggregateGrid(
            listOf(
                bill(31.20, 121.47, 100),
                bill(31.22, 121.47, 500),
                bill(31.22, 121.47, 700),
                bill(31.24, 121.47, 300)
            )
        )
        assertEquals(listOf(1200L, 300L, 100L), ranks.map { it.totalMinor })
    }

    @Test
    fun `只统计支出且必须带位置`() {
        val ranks = SpendGeoProfile.aggregateGrid(
            listOf(
                bill(31.23, 121.47, 1000, type = BillType.INCOME),  // 收入不算消费
                bill(null, 121.47, 500),                            // 未打点
                bill(31.23, null, 500),                             // 未打点
                bill(31.23, 121.47, 800)
            )
        )
        assertEquals(1, ranks.size)
        assertEquals(800L, ranks[0].totalMinor)
        assertEquals(1, ranks[0].billCount)
    }

    @Test
    fun `Top分类取网格内金额最大者`() {
        val ranks = SpendGeoProfile.aggregateGrid(
            listOf(
                bill(31.23, 121.47, 300, category = "三餐"),
                bill(31.23, 121.47, 900, category = "交通"),
                bill(31.23, 121.47, 200, category = "三餐")
            )
        )
        assertEquals("交通", ranks[0].topCategory)
    }

    @Test
    fun `空输入与无有效账单返回空列表`() {
        assertTrue(SpendGeoProfile.aggregateGrid(emptyList()).isEmpty())
        assertTrue(SpendGeoProfile.aggregateGrid(listOf(bill(null, null, 100))).isEmpty())
    }

    @Test
    fun `负坐标网格归属连续`() {
        // 西半球：-121.471 → floor(-12147.1) = -12148，中心 (-12148 + 0.5) × 0.01 = -121.475
        val ranks = SpendGeoProfile.aggregateGrid(listOf(bill(31.23, -121.471, 100)))
        assertEquals(1, ranks.size)
        assertEquals(-121.475, ranks[0].cellLng, 1e-9)
    }

    @Test
    fun `热力半径随占比线性放大且封顶`() {
        assertEquals(1100.0, SpendGeoProfile.radiusMeters(1000, 1000), 1e-9)
        assertEquals(250.0, SpendGeoProfile.radiusMeters(0, 1000), 1e-9)
        val mid = SpendGeoProfile.radiusMeters(500, 1000)
        assertEquals(675.0, mid, 1e-9)
        // 榜首为 0（无数据）时兜底最小半径
        assertEquals(250.0, SpendGeoProfile.radiusMeters(100, 0), 1e-9)
    }

    @Test
    fun `网格边长常量口径`() {
        assertEquals(0.01, SpendGeoProfile.GRID_DEG, 1e-12)
    }

    @Test
    fun `空分类名的Top分类不抛异常`() {
        val ranks = SpendGeoProfile.aggregateGrid(listOf(bill(31.23, 121.47, 500, category = "")))
        assertEquals(500L, ranks[0].totalMinor)
        assertTrue(ranks[0].topCategory == null || ranks[0].topCategory!!.isEmpty())
    }
}
