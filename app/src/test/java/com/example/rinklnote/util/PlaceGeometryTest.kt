package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常去地点纯几何计算单测：距离近似精度、合并阈值判定、最近地点匹配。
 * 数据点使用上海人民广场一带的真实坐标量级。
 */
class PlaceGeometryTest {

    // ── 距离 ──

    @Test
    fun `同一点距离为0`() {
        assertEquals(0.0, PlaceGeometry.distanceMeters(31.2304, 121.4737, 31.2304, 121.4737), 1e-6)
    }

    @Test
    fun `纬度方向约111米每度`() {
        // 0.001 纬度 ≈ 110.6 米
        val d = PlaceGeometry.distanceMeters(31.2304, 121.4737, 31.2314, 121.4737)
        assertEquals(110.6, d, 1.0)
    }

    @Test
    fun `经度方向随纬度收敛`() {
        // 0.001 经度在纬度 31° 处 ≈ 111320 * cos(31°) * 0.001 ≈ 95.4 米
        val d = PlaceGeometry.distanceMeters(31.0, 121.4737, 31.0, 121.4747)
        assertEquals(95.4, d, 1.0)
    }

    @Test
    fun `距离对称`() {
        val d1 = PlaceGeometry.distanceMeters(31.2304, 121.4737, 31.2350, 121.4800)
        val d2 = PlaceGeometry.distanceMeters(31.2350, 121.4800, 31.2304, 121.4737)
        assertEquals(d1, d2, 1e-6)
    }

    @Test
    fun `非法纬度视为不可匹配`() {
        val d = PlaceGeometry.distanceMeters(91.0, 121.0, 31.0, 121.0)
        assertEquals(Double.MAX_VALUE, d, 0.0)
    }

    // ── 最近地点匹配 ──

    private data class P(val id: Long, val lat: Double, val lng: Double)

    private val places = listOf(
        P(1, 31.2304, 121.4737),  // 人民广场
        P(2, 31.2350, 121.4800)   // 约 800 米外
    )

    private fun nearest(lat: Double, lng: Double, radius: Double = PlaceGeometry.MERGE_RADIUS_METERS): P? =
        PlaceGeometry.nearestWithin(places, lat, lng, radius, { it.lat }, { it.lng })

    @Test
    fun `半径内命中最近的一个`() {
        // 距 P1 约 5 米、距 P2 更远 → 命中 P1
        val hit = nearest(31.23044, 121.47372)
        assertNotNull(hit)
        assertEquals(1L, hit!!.id)
    }

    @Test
    fun `半径外返回null`() {
        // 约 800 米外，远超 50 米阈值
        assertNull(nearest(31.2370, 121.4820))
    }

    @Test
    fun `空列表安全`() {
        assertNull(
            PlaceGeometry.nearestWithin(emptyList<P>(), 31.23, 121.47, PlaceGeometry.MERGE_RADIUS_METERS, { it.lat }, { it.lng })
        )
    }

    @Test
    fun `自定义半径生效`() {
        // 1 公里阈值内应命中较近的 P1
        val hit = PlaceGeometry.nearestWithin(places, 31.2370, 121.4820, 1000.0, { it.lat }, { it.lng })
        assertNotNull(hit)
        assertEquals(2L, hit!!.id)  // 该点距 P2 更近
    }

    // ── 阈值口径 ──

    @Test
    fun `合并阈值为50米`() {
        assertEquals(50.0, PlaceGeometry.MERGE_RADIUS_METERS, 0.0)
    }

    @Test
    fun `米级抖动内的两次打点视为同一地点`() {
        // 单次定位抖动约 10 米：两次采集应在阈值内互相匹配
        val a = PlaceGeometry.distanceMeters(31.2304, 121.4737, 31.23047, 121.47376)
        assertTrue(a < PlaceGeometry.MERGE_RADIUS_METERS)
        assertFalse(a < 0)
    }
}
