package com.example.rinklnote.ui.screen.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import dev.chrisbanes.haze.HazeState
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * 「账单地图」预实现（路由 `bill-map` 由主会话接线，对应目标 9）。
 *
 * 现实约束：bills 表**尚无位置字段（lat/lng）**，无真实账单地点数据——本页按三态流程预实现：
 * ① 确认卡：说明「账单地图按消费地点展示账单，需开启定位权限」，「开启」同时请求
 *    ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION（[RequestMultiplePermissions]）→
 * ② 地图态：osmdroid [MapView]（开源 OSM 瓦片，**无需 API key**），放置演示标记占位；
 * ③ 拒绝态：引导卡（说明 + 「去开启」重试 + 返回）。
 *
 * **后续接入点**：bills 表加位置字段后，①删掉 [DemoBillPlaces] 演示标记，改为按账单
 * 聚合真实标记；②初始视野改为按聚合点的包围盒定位（或用户最近一次消费地点）。
 *
 * 材质沿用 App 既有规范：确认/引导卡 15dp 圆角 + rinkShadow + applyCardGlass，
 * 字阶 18/14/16，页面水平 14dp；有自选背景时卡片透出照片（hazeState 非空即挂毛玻璃能力）。
 *
 * @param backgroundUri 自选背景照片 uri（nav 层已整窗铺满时不再自绘背景）；null = 无照片背景
 * @param hazeState nav 层毛玻璃状态；null = 纯色背景兜底
 * @param onBack 返回（顶栏返回键与「返回」按钮共用）
 * @param onRequestEnable 用户在确认卡点「开启」后的后续动作回调（当前可空实现，由调用方注入）
 */
@Composable
fun BillMapScreen(
    backgroundUri: String?,
    hazeState: HazeState?,
    onBack: () -> Unit,
    onRequestEnable: () -> Unit
) {
    val context = LocalContext.current

    // 已授权（任一定位权限）则跳过确认卡直接进地图；仅首次进入走「确认卡 → 授权」流程
    val hasPermission = remember {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    var step by remember { mutableStateOf(if (hasPermission) MapStep.Map else MapStep.Confirm) }

    // 定位权限申请：任一授权即视为成功（粗略定位也能满足「看消费地点分布」的用途）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        step = if (result.values.any { it }) MapStep.Map else MapStep.Denied
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景：有照片时由 nav 层整窗铺满；否则纯白底注册为毛玻璃源（与「导入账单」页一致）。
        // 地图态整屏被 MapView 盖住，此背景只服务于确认卡/引导卡两个状态。
        if (backgroundUri == null) {
            val hs = hazeState
            if (hs != null) {
                DefaultHazeBackground(hazeState = hs)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
        }

        when (step) {
            MapStep.Confirm -> ConfirmCard(
                hasBackground = backgroundUri != null,
                onEnable = {
                    // 确认开启后的后续动作（当前空实现，由调用方注入）
                    onRequestEnable()
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onDecline = onBack
            )

            MapStep.Denied -> DeniedCard(
                hasBackground = backgroundUri != null,
                onRetry = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onBack = onBack
            )

            MapStep.Map -> MapContent(onBack = onBack)
        }
    }
}

/** 页面内部三态：确认卡 → 地图 / 拒绝引导。 */
private enum class MapStep { Confirm, Map, Denied }

// ---------------------------------------------------------------------------
// ① 确认卡 / ③ 拒绝引导卡
// ---------------------------------------------------------------------------

@Composable
private fun ConfirmCard(
    hasBackground: Boolean,
    onEnable: () -> Unit,
    onDecline: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MapPageTopBar(hasBackground = hasBackground, onBack = onDecline)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StepCard(
                hasBackground = hasBackground,
                title = "账单地图",
                body = "账单地图按消费地点展示账单，需开启定位权限。开启后即可在地图上查看你的消费地点分布。",
                confirmLabel = "开启",
                onConfirm = onEnable,
                dismissLabel = "暂不",
                onDismiss = onDecline
            )
        }
    }
}

@Composable
private fun DeniedCard(
    hasBackground: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MapPageTopBar(hasBackground = hasBackground, onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StepCard(
                hasBackground = hasBackground,
                title = "定位权限未开启",
                body = "未获得定位权限，无法在地图上标记消费地点。可重新发起授权；若此前选择了「不再询问」，请前往系统设置手动开启。",
                confirmLabel = "去开启",
                onConfirm = onRetry,
                dismissLabel = "返回",
                onDismiss = onBack
            )
        }
    }
}

/** 确认/引导共用卡：15dp 圆角玻璃卡，居中展示，标题 18sp + 说明 14sp + 底部按钮行。 */
@Composable
private fun StepCard(
    hasBackground: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissLabel,
                    fontSize = 16.sp,
                    color = if (hasBackground) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onConfirm) {
                Text(text = confirmLabel, fontSize = 16.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ② 地图态
// ---------------------------------------------------------------------------

/**
 * 演示标记数据：**bills 表位置字段（lat/lng）接入前的占位**。
 * 接入后删除本列表，替换为真实账单按地点聚合的标记（见类头注释「后续接入点」）。
 */
private data class DemoBillPlace(val lat: Double, val lng: Double, val title: String)

private val DemoBillPlaces = listOf(
    DemoBillPlace(31.2304, 121.4737, "示例 · 午餐 ¥28"),
    DemoBillPlace(31.2455, 121.5028, "示例 · 咖啡 ¥18"),
    DemoBillPlace(31.2230, 121.4400, "示例 · 地铁 ¥4"),
    DemoBillPlace(31.2600, 121.4300, "示例 · 超市 ¥96")
)

/** 默认演示城市：上海人民广场一带（无定位数据时的固定初始视野）。 */
private val DefaultCenter = GeoPoint(31.2304, 121.4737)

@Composable
private fun MapContent(onBack: () -> Unit) {
    val context = LocalContext.current

    // osmdroid 初始化只做一次：UA 必须在首个 MapView 创建前设置（默认空 UA 会被瓦片服务器拒绝）；
    // 瓦片缓存落到应用 cache 目录，避开 Android 10+ 分区存储下默认外部路径不可写的问题。
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // 隐藏 osmdroid 自带缩放按钮（+/-），走双指捏合手势
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(11.0)
            controller.setCenter(DefaultCenter)
            // 演示标记（占位）：账单位置字段接入后替换为真实聚合数据
            DemoBillPlaces.forEach { place ->
                overlays.add(
                    Marker(this).apply {
                        position = GeoPoint(place.lat, place.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = place.title
                    }
                )
            }
        }
    }

    // 生命周期对齐：进出页面启停瓦片下载调度，卸载时解绑（预实现阶段不做 SqlTileWriter 深清理）
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 顶部悬浮返回：ArrowBack 图章钮 + 20sp Medium 标题，配色对齐其他页顶栏；
        // 地图瓦片深浅不定，给 surface 圆底兜底可读性
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .rinkShadow(CircleShape)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .then(applyCardGlass(CircleShape))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "账单地图",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = LocalRinklColors.current.topBarTitleColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** 确认卡/引导卡态的悬浮顶栏：ArrowBack + 居中标题「账单地图」（20sp Medium），对齐「导入账单」页。 */
@Composable
private fun MapPageTopBar(hasBackground: Boolean, onBack: () -> Unit) {
    val textColor = if (hasBackground) Color.White else LocalRinklColors.current.topBarTitleColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack
                )
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "账单地图",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
