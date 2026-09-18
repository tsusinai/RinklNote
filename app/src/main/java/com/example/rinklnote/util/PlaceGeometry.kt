package com.example.rinklnote.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 常去地点的纯几何工具（纯 JVM，无 Android 依赖，可单测）。
 *
 * 距离用等距圆柱近似（equirectangular）：在数百米尺度上与 Haversine 误差可忽略，
 * 且无需三角函数密集计算。输入/输出一律「度」。
 */
object PlaceGeometry {

    /**
     * 同一地点的判定半径（米）：定位单次采集天然带 10~30 米抖动（GPS/网络），
     * 50 米内视为同一地点（合并/建议共用此阈值）。
     */
    const val MERGE_RADIUS_METERS = 50.0

    /** 每纬度 1 度的米数（常数近似，纬度影响 <0.5%，可忽略）。 */
    private const val METERS_PER_DEG_LAT = 110_574.0

    /** 每经度 1 度的米数（随纬度收敛，取 cos(纬度) 折算）。 */
    private const val METERS_PER_DEG_LNG_EQ = 111_320.0

    /**
     * 两点近似距离（米）。任一入参非法（|lat|>90）返回 Double.MAX_VALUE（视为不可匹配，
     * 由调用方兜底），不抛异常。
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (abs(lat1) > 90.0 || abs(lat2) > 90.0) return Double.MAX_VALUE
        val dy = (lat2 - lat1) * METERS_PER_DEG_LAT
        val meanLatRad = ((lat1 + lat2) / 2.0) * PI / 180.0
        val dx = (lng2 - lng1) * METERS_PER_DEG_LNG_EQ * cos(meanLatRad)
        return hypot(dx, dy)
    }

    /**
     * 在已知地点里找 (lat, lng) 半径 [radiusMeters] 内**最近**的一个；没有则返回 null。
     * 同距离的并列取列表顺序（调用方传入「最近使用在前」即稳定）。
     */
    fun <T> nearestWithin(
        places: List<T>,
        lat: Double,
        lng: Double,
        radiusMeters: Double = MERGE_RADIUS_METERS,
        latOf: (T) -> Double,
        lngOf: (T) -> Double
    ): T? {
        var best: T? = null
        var bestDistance = Double.MAX_VALUE
        for (place in places) {
            val d = distanceMeters(lat, lng, latOf(place), lngOf(place))
            if (d <= radiusMeters && d < bestDistance) {
                best = place
                bestDistance = d
            }
        }
        return best
    }
}
