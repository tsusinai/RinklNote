package com.example.rinklnote.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 一次性位置采集器（账单打点用）。
 *
 * 隐私口径：**只在用户记/编账单时主动点「位置」按钮的瞬间采集一次**——
 * 不做后台定位、不持续监听、不缓存结果；采集到的经纬度仅随该笔账单
 * 落库与同步，用于「账单地图」展示。无定位权限时直接返回 null（这里
 * 不弹权限申请，失败提示由调用方负责）。
 *
 * 实现走系统 [LocationManager]，不引入 play-services 新依赖：
 * 1) 优先取 Network / GPS 两个 provider 的 lastKnownLocation（取时间戳更新者）；
 * 2) 都没有时挂一次单次更新，超过 [timeoutMs] 仍未定位成功则返回 null。
 */
object LocationGrabber {

    /**
     * 采集结果（体验审查 P1）：成功给坐标；失败带原因分类，调用方据此给出
     * 「去设置 / 重试」等可行动反馈，而不是笼统的「定位失败」。
     */
    sealed interface GrabResult {
        /** 定位成功：纬度、经度（度，WGS84）。 */
        data class Coords(val latitude: Double, val longitude: Double) : GrabResult

        /** 定位失败：[reason] 分类见 [LocationFailureReason]。 */
        data class Failure(val reason: LocationFailureReason) : GrabResult
    }

    /** 定位失败原因分类（给 UI 判定「去设置」还是「重试」）。 */
    enum class LocationFailureReason {
        /** 未授予定位权限：可以再次发起权限申请（「重试」）。 */
        PERMISSION_DENIED,

        /** 权限已被永久拒绝（不再弹窗）：只能去系统设置开启（「去设置」）。 */
        PERMISSION_PERMANENTLY_DENIED,

        /** 系统定位服务开关关闭：引导去设置开启（「去设置」）。 */
        LOCATION_SERVICE_OFF,

        /** 已授权、服务也开着但定位失败/超时：可直接重试。 */
        TIMEOUT_OR_UNAVAILABLE
    }

    /** 采集一次当前位置（带失败原因分类），供需要区分失败原因的调用方使用。 */
    suspend fun grabWithReason(context: Context, timeoutMs: Long = 4000L): GrabResult {
        val activity = context as? android.app.Activity
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return GrabResult.Failure(LocationFailureReason.TIMEOUT_OR_UNAVAILABLE)

        // GPS_PROVIDER 需要 FINE；NETWORK_PROVIDER 有 COARSE 或 FINE 任一即可。
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            // 「永久拒绝」的判定尽力而为：不再弹说明（shouldShowRationale=false）
            // 且非首次（有过拒绝记录）时视为永久拒绝；系统判定在 API 30+ 更准，这里按可用信息分类。
            val canAskAgain = activity == null || androidx.core.app.ActivityCompat
                .shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            return GrabResult.Failure(
                if (canAskAgain) LocationFailureReason.PERMISSION_DENIED
                else LocationFailureReason.PERMISSION_PERMANENTLY_DENIED
            )
        }

        // 定位服务（系统开关）总开关：两个 provider 都没开时按「定位服务关闭」引导。
        val anyProviderEnabled = listOf(
            LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER
        ).any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (!anyProviderEnabled) return GrabResult.Failure(LocationFailureReason.LOCATION_SERVICE_OFF)

        // 1) lastKnownLocation：两 provider 各取一份，取时间戳更新者。
        val lastKnown = buildList {
            if (hasCoarse || hasFine) add(runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull())
            if (hasFine) add(runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull())
        }.filterNotNull().maxByOrNull { it.time }
        if (lastKnown != null) return GrabResult.Coords(lastKnown.latitude, lastKnown.longitude)

        // 2) 单次更新：只挂已启用且有权限的 provider，首个回调即返回。
        val providers = buildList {
            if (hasCoarse || hasFine) add(LocationManager.NETWORK_PROVIDER)
            if (hasFine) add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return GrabResult.Failure(LocationFailureReason.LOCATION_SERVICE_OFF)

        val located = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // 首个回调即收口，撤掉其余 provider 的挂载
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }

                    // minSdk 28 的旧平台这三个回调没有默认实现，必须显式空实现兜底
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                try {
                    // 回调统一投递到主线程 Looper（当前协程可能在无 Looper 的调度线程上）
                    val mainLooper = Looper.getMainLooper()
                    providers.forEach { lm.requestSingleUpdate(it, listener, mainLooper) }
                } catch (_: Exception) {
                    runCatching { lm.removeUpdates(listener) }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return if (located != null) GrabResult.Coords(located.latitude, located.longitude)
        else GrabResult.Failure(LocationFailureReason.TIMEOUT_OR_UNAVAILABLE)
    }

    /**
     * 采集一次当前位置。
     * @return (纬度, 经度)，单位度（WGS84）；无权限 / 无可用 provider / 超时 → null。
     */
    suspend fun grab(context: Context, timeoutMs: Long = 4000L): Pair<Double, Double>? =
        (grabWithReason(context, timeoutMs) as? GrabResult.Coords)?.let { it.latitude to it.longitude }
}
